# The `bot/` layers

Everything behind `mc.bot.*` — the autonomous layer that plans and drives a body. Read
[`architecture.md`](architecture.md) first; this document picks up where `DriverApi.route`
hands off.

`bot/` is the largest and fastest-changing subsystem in the repository. **This document
describes seams and invariants, not inventories.** It deliberately lists no processes, no
knobs and no priority numbers, because those change from week to week and a stale list is
worse than none. For the current set, read the package; for how the pieces fit, read this.

## The spine

```
mc.bot.<verb>
  └─ BotApi              facade — returns fast, work continues on the tick
      └─ BotApiImpl      binds the chains, owns the scheduler
          └─ ProcessScheduler   one movement channel, chains bid per tick
              └─ Chain          a policy: how badly it wants the channel this tick
                  └─ BotProcess one verb's behaviour, ticked
                      └─ Walker executes a path
                          └─ PathFinder  A* over the Move catalog
```

Each layer is a seam that can be changed without touching its neighbours. That is the
point of the shape.

## The facade

`BotApi` lives in `common` so a dedicated-server JVM never resolves it; a Fabric or
NeoForge client entry point instantiates the platform implementation and binds it through
`BotHooks`.

Most verbs return immediately with `{started:true, …}` and continue on subsequent ticks.
Progress is observed through `status()` plus `mc.wait.condition`, and cancellation is
cooperative: calling another action implicitly cancels the previous one of the same kind.

Not all of them behave that way. A family of verbs is documented `Synchronous` and takes
no process slot — equipping, holding an item, and the instant interaction verbs among
them. The interface's class-level javadoc still says every method returns immediately; the
per-method javadocs contradict it. Trust the method. Grepping `Synchronous` in
`BotApi.java` gives the current set, which moves.

## The movement channel

There is exactly one movement channel, owned by `ProcessScheduler`. Chains register with
it and bid a `priority` each tick; the highest bidder runs. Registration order does not
matter.

This replaced a single `volatile BotProcess current` slot with a preempt-and-resume model:
a reflex chain can take the channel from a long-running user task and hand it back when
the situation clears, firing the interrupted chain's `onInterrupt` and `onResume` across
the switch. `Priorities` declares the bands and explains why each sits where it does; the
ladder itself is the `scheduler.register(...)` block in `BotApiImpl`, with the bid written
beside each line. Read those two places rather than any prose summary — the foreground
user task is not the bottom of the ladder, and at least one idle-only chain deliberately
bids below it.

`Priorities.HYSTERESIS` is an anti-flap margin: a challenger must beat the incumbent's
last bid by more than that margin to take the channel, so two chains bidding nearly the
same value do not trade the slot every tick.

The scheduler is driven from the client tick thread only, so its `current` field needs no
synchronization. Status reporting may run on a transport handler thread, which is why
`currentName` and the per-chain `lastPriorities` map are published as volatile snapshots:
an off-thread reader always sees a complete, internally consistent map rather than one
mid-mutation. New off-thread state should follow that pattern.

## A process is one verb, ticked

`BotProcess` is a behaviour driven once per tick; the implementations live in `process/`.
Its one entry point is `tick(Body, WorldView, BotState)`. The scheduler's chains hand a
process the body the scheduler was given, and the client tick chain builds a
`ClientPlayerBody` once per tick for that purpose. A process that names `Minecraft` in its
own signature will not run on a server body.

Two optional hooks come with caveats that are recorded in `BotProcess`'s own javadoc and
are worth reading before relying on either. `onResume()` is called when a process regains
the channel after preemption so it can repath from where the body actually is; only one
process in the package implements it, and the rest inherit the no-op and resume on a path
computed from a position the body may have been dragged out of. `onCancelled(String)` is
called when a process is superseded before finishing; overriding it and finalising the
walker are two different things, and counting overrides does not answer the second
question.

The chains talk in bodies too — `Chain.priority(Body, …)` and `Chain.tick(Body, …)` — but
the chain implementations still read the local player, the client level and the
client-only helpers through `Minecraft`, so each downcasts at the top of both methods via
`Chain.clientOf(body)`. That returns null for any body that is not the client's, which is
also what the headless scenes pass when they tick the scheduler with no body.

## `Walker` — executing a path

`Walker` consumes a path and drives the body along it, reporting `Step.WALKING`,
`ARRIVED` or `FAILED`.

Two things regularly surprise contributors.

**Unplanned falls are not the walker's job.** Water-bucket clutch handling lives in the
always-on `ClutchController`, ticked at the top of `clientTick`, so a falling body
self-rescues whether or not a walker is driving. The walker only arms a *planned* fall as
it steps off a lip, and biases the step-off keys so the body drops close to vertically.

**Its static counters are monotone and never reset.** `Walker.lastStats` and counters such
as `descentHolds` accumulate for the life of the process; a caller that wants a window
takes a difference. They exist so a test can tell "the guard cost nothing" apart from "the
guard never ran" — two readings that look identical from outside and call for opposite
conclusions.

The walker's per-tick work is split into `WalkerTick*` phase classes.
[`movement-tick-phases.md`](movement-tick-phases.md) covers that decomposition and the
data that flows between phases; read it before touching a phase.

## `PathFinder` — A* over a move catalog

`findPath` is single-threaded and runs to completion, and the caller is responsible for
invoking it off-thread when the path may be large. There is also a resumable form:
`newSearch(...)` returns a `Search` whose `advance(long)` does as much work as fits in a
wall-clock slice and reports whether it is done. The algorithm and the result are the
same — only the time is chunked, which is what keeps a long search from hitching the
render thread.

A search terminates on reaching the goal, on the node budget (`DEFAULT_MAX_NODES`), on the
wall-clock budget (`DEFAULT_MAX_MS`), or on an empty open set. In every non-goal case it
returns a **best-effort segment** rather than nothing, because Minecraft worlds are mostly
unreachable in some direction and progress beats standing still. The fallback uses
incremental cost backoff rather than the lowest-heuristic node, which may have wandered
far away to shave a little off the estimate: it tracks the best node under several
weightings of travelled cost against remaining estimate and commits to the most
conservative one that still covers `MIN_DIST_PATH`. Repeated calls from successive segment
ends give long-distance splicing for free.

Three extension families are what a contributor actually touches:

| Package | What it is |
|---|---|
| `pathfinder/moves/` | One file per concrete `Move` — an A* edge with a cost and its world preconditions |
| `pathfinder/constraints/` | Hard validity gates: a move is legal or it is not |
| `pathfinder/modifiers/` | `CostModifier`s — additive taxes that steer without forbidding |

Costs are in tenths of a tick, roughly ten per tick of expected travel, and `Move` owns
the shared constants plus the `ALL` catalog assembled from its subclasses. Prefer a
`CostModifier` over a new hard constraint: a tax steers the search, while a gate can make
a region unreachable and turn a slow path into no path.

## The `Body` seam

`Walker` and every process drive a `Body` (package `bot/body/`), not a player. A body's
`entity()` is a `LivingEntity`, so everything the walker reads — position, ground contact,
water, velocity, bounding box, health — and every pose it sets is identical on a client
`LocalPlayer`, a server `ServerPlayer` and a driven mob. The interface itself carries only
what every body has: locomotion impulse and the look.

What only a body with an inventory has sits behind two optionals: `Hands` (hold, place,
break, swing, use) and `Containers` (recipe book, container clicks, closing). A process
that needs them asks at the top of its tick and, when the answer is empty, stamps its
slot's `lastError` with the `no_hands` reason from `BodyReady` and finishes; a caller that
never asks cannot compile a call to them. `asPlayer()` is the raw `Player` view for the
few readers of a player's own state such as food, abilities and the attack cooldown, and
is null for a body that is not one. The walker, which cannot refuse an order, drives
`WalkerNoHands` for a handless body, so its dig and place gates fall closed on their own
readings.

`ClientPlayerBody` and `ServerPlayerBody` implement all three interfaces, so a caller
holding either concrete type is unchanged. That is what lets one process run on a client
body and headless on a server tick, and what the test scenes' driven-mob body plugs into.

### Naming a body

`bot/body/BodyRegistry` holds the bodies the API can address besides the client's own.
Whoever creates a host registers it: `/worlddriver server spawn <name>` registers
`player:<name>`, the test content registers `npc:<name>`, and a third-party mod registers
whatever it builds. Transport threads read the registry and the server thread writes it,
so every method synchronizes on the map; iteration is in registration order, which is the
order `mc.bot.status` lists bodies in. The registry empties when the server stops, since
every host wraps an entity of that server.

The `mc.bot.*` verbs that drive a body take a `body` parameter; `equip`, `setting`,
`waypoint` and `playbook` stay with the client. Anything other than `self` goes to
`api/BodyRoutes`, which hops to the server thread and refuses in `BodyReady` terms judged
on the entity. A process verb's parameters are read by `bot/VerbOrders`, the same builder
`BotApiImpl` uses for `self`, so the two cannot read one order differently. The hand verbs
are `api/BodyInteractions`, which does on the server what a client click's packets would
have done there, reach check included. A host runs one process on the server tick with no
scheduler, chains or reflexes; `self` keeps all three. `BodyRoutes`, `BodyInteractions`
and `VerbOrders` must not name a client class — on a dedicated server they are the only
`mc.bot.*` code that runs.

How faithfully a server-side body reproduces a real player, and every known divergence, is
tabulated in [`fake-player-parity.md`](fake-player-parity.md). Read it before assuming a
vanilla behaviour survives the seam, and do not duplicate its claims here.

## Settings

Two classes, one surface:

- `BotConfig` holds the knobs themselves as mutable volatile statics. It is common- and
  server-safe, so a dedicated-server scene can read it.
- `SettingsRegistry` is the single source of truth for the `mc.bot.setting` key surface.
  The read snapshot, the MCP schema and the write path all derive from it, and applying an
  unrecognised key is rejected.

The registry exists because that knowledge once lived in three places that drifted, and a
newly declared flag whose schema and write branch lagged behind was silently dropped by
the apply path: accepted, echoed back as set, and doing nothing. The reflective completion
pass by which a new flag enters the surface is a single method consumed by both the
registry and the snapshot, so they cannot diverge, and a class-load self-check throws on a
misspelled field name. A documentation entry pointing at a renamed or deleted key throws
at class load too.

All of that keeps the key set, the snapshot, the schema and the documentation agreeing
about which knobs *exist*. None of it asks whether a knob does anything. A field added to
`BotConfig` surfaces automatically, so a flag whose read site never landed — or whose
behaviour was later refactored away, leaving the knob behind — is accepted and echoed as
set while changing nothing, and the caller gets every signal that it worked.
`SettingsConsumerTest` in `common/src/test` closes that side by failing the build when a
key is never read outside the settings pipeline. **Deleting a behaviour means deleting its
flag in the same change.**

## The source budget

`scripts/check_source_budget.py` caps two things, and both apply here: no hand-written
Java file may exceed the file limit, and no method may exceed the method limit unless it
is on the script's grandfather list, in which case it may shrink but never grow. Read the
script for the current numbers. `Walker.java` and `BotConfig.java` both sit close to the
file cap, so adding to either means extracting first; shaving comments to buy a few lines
trades readability for a number the check cannot tell apart from real work.

The `WalkerTick*` phase classes are the reference pattern for carving up a large
sequential method. Five of them are on the grandfather list precisely because that split
moved text without decomposing the method — which is why the method budget exists at all.

## The rest of the packages

| Package | What it is |
|---|---|
| `body/` | `Body`, `Hands`, `Containers`, `BodyCapabilities`, `BodyRegistry`, `ClientPlayerBody` |
| `movement/` | `Walker`, the `WalkerTick*` phases, the look and input controllers, the clutch |
| `pathfinder/` | A*, the move catalog, constraints, cost modifiers |
| `process/` | One class per verb behaviour |
| `scheduler/` | `ProcessScheduler`, `Chain`, `Priorities` and the chain implementations |
| `world/` | The bot's world model: `WorldView` implementations, hazard fields, scene model, survival facts |
| `sim/` | Server-side bodies and their hosts |
| `debug/` | Path archive and replay, chart rendering, probe tools — instrumentation, strippable |
| `auto/` | Always-on ambient behaviours that are not chains (eat, heal, shield, swim, tool, …) |
| `combat/`, `elytra/` | Domain helpers behind the matching processes |
| `util/` | Shared helpers |

`FocusPolicy` and `MouseYield` govern how the bot shares keyboard and mouse with a human
at the same client; read the input-sharing section of [`AGENTS.md`](../../AGENTS.md)
before changing them.

## Before pushing a change here

Behaviour in this subsystem is judged by scenes, not unit tests. [`testing.md`](testing.md)
explains how to run them and how to add one. Two rules bite hardest here: a new scene must
be registered and listed in the manifest in the same change, and the `[walker]` and
`[expect]` log formats are a contract that several development tools parse by regular
expression — a regular expression that stops matching does not raise, it returns nothing,
which reads as "no problem found".
