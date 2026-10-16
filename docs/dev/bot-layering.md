# The `bot/` layers

Everything behind `mc.bot.*` — the autonomous layer that plans and drives a bot — and the
`game/` layer it drives through. Read [`architecture.md`](architecture.md) first; this
document picks up where `ApiRegistry.route` hands off.

`bot/` is the largest and fastest-changing subsystem in the repository. **This document
describes seams and invariants, not inventories.** It deliberately lists no processes, no
knobs and no priority numbers, because those change from week to week and a stale list is
worse than none. For the current set, read the package; for how the pieces fit, read this.

## The layers

The bot is a stack. Each package may use the ones below it in this table, then `game/` and
`config/`, and nothing above it:

| Package | What it is |
|---|---|
| `bot/` | The facade: `BotApi`, `BotBrain` (one bot's decision stack; the client's `BotApi` is `application/client/ClientBot` around one), the verb orders, the status view, the addressable bots (`Bot`, `PlayerBot`, `BotRegistry`, `BotTicker`, `BotCommand`), the `mc.bot.setting` verb |
| `bot/arbitration/` | Who gets the one movement channel: `ProcessScheduler`, `Chain`, `Priorities`, the chain implementations, `HeldProcess` |
| `bot/behavior/` | One class per verb (`BotProcess` and its implementations), `Readiness`, and in `reflex/` the always-on ambient behaviours (eat, heal, shield, swim, tool, …) |
| `bot/control/` | Executing a path: `Walker`, the `WalkerTick*` phases, aim and path smoothing, the clutch |
| `bot/planning/` | Searching for a path: `PathFinder` and the elytra planner |
| `bot/rules/` | What a search may do: `Move` and `MoveCatalog`, and in `moves/`, `constraints/` and `modifiers/` the moves, hard limits and cost taxes |
| `bot/world/` | The bot's picture of the world: `WorldView` and its implementations, hazard fields, threat scans, survival facts |

Below the bot stack:

| Package | What it is |
|---|---|
| `game/observe/` | Reads every caller shares — recipes, item counts, block matches, registry ids |
| `game/body/` | Driving one entity: `Body` and its capabilities (below) |
| `game/edit/` | Operator writes that go through no body: fills, snapshots and their restore, commands |
| `game/ui/` | What the client's screens and input share: the client thread, focus, mouse yield, the chat log |
| `config/` | The settings, one class per layer (see [Settings](#settings)) |

`game/` speaks no contract — only the facade in `bot/` itself speaks the api — and a
`game/` package may use only `game/`, `game/observe/` and the api's thread hop. Code
outside the bot reaches it only through the facade; `BotProcess` is the one type the
facade hands out whole.

`scripts/check_architecture.py` enforces all of this on every source file. What it still
finds is listed in `scripts/architecture-pending.txt`, which may only shrink: a listed line
that stops occurring fails the check too. It is empty now: no class in the bot layers names a
client type. What only a client has — the window, the mouse, the death screen, the chat
surfaces — lives in `application/client/` around the client bot's `BotBrain`, and the client's
own `Level` and `Player` reach `ClientWorldView` as suppliers the client wiring hands in.

## The spine

```
mc.bot.<verb>
  └─ BotApi              facade — returns fast, work continues on the tick
      └─ ClientBot       the client's player: client-only work around one brain
          └─ BotBrain    one bot's chains, reflexes, threat scan, world model, clutch
                         (a server PlayerBot holds one too, ticked by BotTicker)
              └─ ProcessScheduler   one movement channel, chains bid per tick
              └─ Chain          a policy: how badly it wants the channel this tick
                  └─ BotProcess one verb's behaviour, ticked
                      └─ Walker executes a path
                          └─ PathFinder  A* over the move catalog
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
ladder itself is the `scheduler.register(...)` block in `BotBrain`, with the bid written
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

`BotProcess` is a behaviour driven once per tick; the implementations live in
`bot/behavior/`. Its one entry point is `tick(Body, WorldView, BotState)`. The scheduler's
chains hand a process the `Body` the scheduler was given, and the client tick chain builds a
`ClientPlayerBody` once per tick for that purpose. A process that names `Minecraft` in its
own signature will not run on a server-side player.

Two optional hooks come with caveats that are recorded in `BotProcess`'s own javadoc and
are worth reading before relying on either. `onResume()` is called when a process regains
the channel after preemption so it can repath from where the bot actually is; only one
process in the package implements it, and the rest inherit the no-op and resume on a path
computed from a position the bot may have been dragged out of. `onCancelled(String)` is
called when a process is superseded before finishing; overriding it and finalising the
walker are two different things, and counting overrides does not answer the second
question.

The chains take a `Body` too — `Chain.priority(Body, …)`, `Chain.tick(Body, …)`,
`onInterrupt(Body, …)` and `cancelEpisode(Body, …)` — and steer only through it: the player is
`body.entity()`, digging and placing go through `blockInteraction()`, the hands through
`equipment()`. No chain names a client type, so the same chains run over any body. The headless
scenes pass a null `Body` when they tick the scheduler to exercise the bidding alone.

## `Walker` — executing a path

`Walker` consumes a path and drives the bot along it, reporting `Step.WALKING`,
`ARRIVED` or `FAILED`.

Two things regularly surprise contributors.

**Unplanned falls are not the walker's job.** Water-bucket clutch handling lives in the
always-on `ClutchController`, one per bot, ticked at the top of the bot's tick, so a falling bot
rescues itself whether or not a walker is driving. The walker only signals a *planned* fall as
it steps off a lip, and biases the step-off keys so the bot drops close to vertically.

**What a walker reports goes through `MoveSignals`.** Each `WorldView` carries one: the walker
says it is digging or cruising underwater, a flee process says it is fleeing, the edge guards
plan a clutch. The bot clears it every tick, once the clutch has taken last tick's plan and
before the scheduler runs. These used to be static flags, which every bot in the JVM shared.

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
| `bot/rules/moves/` | One file per concrete `Move` — an A* edge with a cost and its world preconditions |
| `bot/rules/constraints/` | Hard validity limits: a move is legal or it is not |
| `bot/rules/modifiers/` | `CostModifier`s — additive taxes that steer without forbidding |

Costs are in tenths of a tick, roughly ten per tick of expected travel, and `Move` owns
the shared constants plus the `ALL` list assembled from its subclasses. Prefer a
`CostModifier` over a new hard constraint: a tax steers the search, while a hard limit can
make a region unreachable and turn a slow path into no path.

Every move also names the way of moving it starts in — walking or swimming in `ALL` — and
`MoveCatalog` partitions `ALL` by that answer. A search takes the catalogs of the modes the
controlled entity has (`WorldView.moveModes()`, read from its `Locomotion`), in `ALL`'s
order, because the search breaks ties by insertion. A player walks and swims, so its
catalog is all of `ALL`. A body that lives in one medium — it flies, or it swims and does not
walk (`MoveCatalog.medium`) — gets `MediumMove.FLY` or `MediumMove.SWIM` instead: strokes to any
of the 26 neighbours through that medium only, kept out of `ALL`. A stroke checks every cell the
body's box covers on the way (`WorldView.bodyWidth()` and `bodyHeight()`), so a large body is never
planned through a hole it does not fit. Its search drops the "get out of the water" handling and the
depth penalty a player's needs, and `Walker` hands its ticks to `WalkerMedium`, which follows the
nodes in three dimensions. Riding has no moves yet.

## The `Body` seam

`Walker` and every process drive a `Body` (package `game/body/`), not a player. A `Body`'s
`entity()` is a `LivingEntity`, so everything the walker reads — position, ground contact,
water, velocity, bounding box, health — and every pose it sets is identical on a client
`LocalPlayer`, a server `ServerPlayer` and a driven mob. The interface itself carries only
what every driven entity has: locomotion impulse and the look.

What only some entities have sits behind one optional per capability: `Equipment` (hold, use
what is held), `Attack` (swing), `BlockInteraction` (place, break, use a block), `Bag` (what
is carried) and `Menus` (recipe book, container clicks, closing). A process that needs them
asks at the top of its tick and, when one is empty, stamps its slot's `lastError` with the
`no_hands` reason from `Readiness` and finishes; a caller that never asks cannot compile a
call to them. `asPlayer()` is the raw `Player` view for the few readers of a player's own
state such as food, abilities and the attack cooldown, and is null for an entity that is
not a player. The walker, which cannot refuse an order, drives `WalkerNoHands` for an entity
without equipment or block interaction, so its dig and place checks fail closed on their own
readings.

How the entity moves — its size, how high it steps and jumps, its speeds and which of
walking, swimming and flying it has — is `Locomotion`, read off the entity's attributes
rather than written down per kind. For a mob, which modes it has is read from its navigation
first (water-bound, amphibious, flying) and its vanilla base class second.

`ClientPlayerBody` and `ServerPlayerBody` implement every capability, so a caller holding
either concrete type is unchanged. That is what lets one process run on a client player and
headless on a server tick, and what the test scenes' driven-mob `Body` plugs into. The
server-side players come from `JoinedPlayers`: a `ServerPlayer` that has joined through the
player list, with a connection that goes nowhere. `MobBody` drives any vanilla `Mob` with its
goals taken off: `MobMixin` writes the input into the mob's own AI step after its move control,
and the body implements no optional capability.

### Naming a bot

A `Bot` is a body and the stack that drives it, addressed by name. `ServerBot` holds the
`BotBrain` and task bookkeeping every server bot shares; `PlayerBot` extends it over a joined
server player and `MobBot` over a `MobBody`, and in the test content `NpcBot` drives the
testmod's piglin. `BotTicker`
advances every registered bot once per server tick. A bot whose task has ended stays on the
list, idle, so its reflexes keep running; it leaves when it is unregistered, when its entity is
removed, or when its tick throws. `BotTicker.activeCount()` counts only the bots on a task.
`BotRegistry` holds the bots the API can address besides the client's own player. Whoever
creates a bot registers it: `/worlddriver server spawn <name>` (`BotCommand`) registers
`player:<name>`, `/worlddriver mob <entity> <name>` registers `mob:<name>`, the first order
naming `entity:<uuid>` registers a loaded mob under that ref (`BotRegistry.resolve`; a status
read or a cancel never does), the test content registers `npc:<name>`, and a third-party mod
registers whatever it builds. Unregistering a mob bot releases it: its goals go back on. Transport threads read the registry and the server thread writes it,
so every method synchronizes on the map; iteration is in registration order, which is the
order `mc.bot.status` lists bots in. The registry empties when the server stops, since
every bot wraps an entity of that server.

The `mc.bot.*` verbs that drive a bot take a `bot` parameter; `equip`, `setting`,
`waypoint` and `playbook` stay with the client. Anything other than `self` goes to
`application/BodyRoutes`, which hops to the server thread and refuses in `Readiness` terms
judged on the entity. A process verb's parameters are read by `bot/VerbOrders`, the same
builder `BotBrain` uses for `self`, so the two cannot read one order differently. The hand
verbs are `application/BodyInteractions`, which does on the server what a client click's
packets would have done there, reach check included. A `PlayerBot` runs its own `BotBrain`
on the server tick — the scheduler, the chains and the reflexes `self` has, with the same
defaults — and `self` is not yet a `Bot`. A server bot's brain sends no events yet: the agent's
event stream carries no bot id, so one would read as the client player's. `BodyRoutes`, `BodyInteractions` and `VerbOrders` must not name a client class — on a
dedicated server they are the only `mc.bot.*` code that runs.

How faithfully a server-side player reproduces a real player, and every known divergence, is
tabulated in [`fake-player-parity.md`](fake-player-parity.md). Read it before assuming a
vanilla behaviour survives the seam, and do not duplicate its claims here.

## Settings

Two kinds of class, one surface:

- The `config/*Config` classes hold the knobs themselves as mutable volatile statics, one
  class per layer: each knob sits in the lowest layer that reads it, and a layer reads only
  its own class and the ones below (`check_architecture.py` holds it to that). `BotConfig`
  holds the facade's own and enumerates, persists and pins them all. They are common- and
  server-safe, so a dedicated-server scene can read them.
- `SettingsRegistry`, beside them in `config/`, is the single source of truth for the
  `mc.bot.setting` key surface.
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
a config class surfaces automatically, so a flag whose read site never landed — or whose
behaviour was later refactored away, leaving the knob behind — is accepted and echoed as
set while changing nothing, and the caller gets every signal that it worked.
`SettingsConsumerTest` in `common/src/test` closes that side by failing the build when a
key is never read outside the settings pipeline. **Deleting a behaviour means deleting its
flag in the same change.**

## The source budget

`scripts/check_source_budget.py` caps two things, and both apply here: no hand-written
Java file may exceed the file limit, and no method may exceed the method limit unless it
is on the script's grandfather list, in which case it may shrink but never grow. Lines that
hold only a comment do not count, so explaining code costs nothing against either limit.
Read the script for the current numbers.

The `WalkerTick*` phase classes are the reference pattern for carving up a large
sequential method. Four of them are on the grandfather list precisely because that split
moved text without decomposing the method — which is why the method budget exists at all.

`FocusPolicy` and `MouseYield` (in `game/ui/`) govern how the bot shares keyboard and mouse
with a human at the same client; read [`input-sharing.md`](input-sharing.md) before changing
them.

## Before pushing a change here

Behaviour in this subsystem is judged by scenes, not unit tests. [`testing.md`](testing.md)
explains how to run them and how to add one. Two rules bite hardest here: a new scene must
be registered and listed in the manifest in the same change, and the `[walker]` and
`[expect]` log formats are a contract that several development tools parse by regular
expression — a regular expression that stops matching does not raise, it returns nothing,
which reads as "no problem found".
