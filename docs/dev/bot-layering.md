# The `bot/` layers

For people **changing the autonomous layer** — everything behind `mc.bot.*`. Start
with [`architecture.md`](architecture.md) if you have not read it; this file picks
up where `DriverApi.route` hands off.

`bot/` is the largest and fastest-churning subsystem in the repo. **This file
describes seams and invariants, not inventories** — no lists of processes or
knobs, because those change weekly and a stale list is worse than none. When you
need the current set, read the package; when you need to know *how the pieces
fit*, read this.

---

## The spine

```
mc.bot.<verb>
  └─ BotApi            facade — returns fast, work continues on the tick
      └─ BotApiImpl    binds the chains, owns the scheduler
          └─ ProcessScheduler   one movement channel, chains bid per tick
              └─ Chain          a policy: when do I want the channel?
                  └─ BotProcess one verb's behaviour, ticked
                      └─ Walker executes a path
                          └─ PathFinder  A* over the Move catalog
```

Each layer below is a seam you can change without touching its neighbours. That
is the whole point of the shape.

---

## 1. `BotApi` — the facade

Lives in `common` so a dedicated-server JVM never resolves it; a Fabric/NeoForge
client entrypoint instantiates the platform impl and binds it through
`BotHooks`.

**Most verbs return immediately** with `{started:true, …}` and run on subsequent
ticks — progress is observed via `status()` plus `mc.wait.condition`, and
cancellation is cooperative (calling another action implicitly cancels the
previous one of the same kind).

⚠️ **Not all of them.** A family of verbs is documented `Synchronous` and takes
**no process slot** — equipping, holding an item, and the instant interaction
verbs among them. The class-level javadoc on `BotApi` still says "every method
returns immediately"; the per-method javadocs contradict it. Trust the method,
and grep `Synchronous` in `BotApi.java` rather than either sentence — the set
moves.

## 2. `ProcessScheduler` — the movement channel

There is exactly **one movement channel**. Chains register with it and each tick
bid a `priority`; only the highest bidder runs. Registration order is
irrelevant.

This replaced a single `volatile BotProcess current` slot with a
**preempt/resume** model: a panic or combat chain can take the channel from a
long-running user task and hand it back when the threat clears, firing the
interrupted chain's `onInterrupt` / `onResume` across the switch.

The ladder is declared in one place — the `scheduler.register(...)` block in
`BotApiImpl`, with the bid written beside each line. Reflexes outrank
deliberation: blast and projectile evasion at the top, then drowning escape,
opt-in bunkering, low-HP retreat, active combat, and the **foreground user task
lowest of all**. Read that block for the live numbers; do not copy them into
prose, here or anywhere.

**Threading.** The scheduler is driven from the client tick thread *only*, so
`current` needs no synchronization. Status reporting may run on an RPC handler
thread, which is why `currentName` and the per-chain `lastPriorities` map are
published as volatile snapshots — an off-thread reader always sees a complete,
internally consistent map rather than one mid-mutation. If you add off-thread
state, follow that pattern.

## 3. `BotProcess` — one verb, ticked

A behaviour driven once per tick; implementations all live in `process/`.

The one method is `tick(Body, WorldView, BotState)`. The scheduler's chains hand
a process the body the scheduler was given, and the client tick chain builds a
`ClientPlayerBody` once per tick for that. There used to be a
`tick(Minecraft, …)` default bridge for the client callers; nothing overrode it and
nothing calls it now, so it is gone. A process that names `Minecraft` in its own
signature is a process that will not run on a server body.

Adding a process means implementing that method. There is no "not yet migrated"
state to be in.

The scheduler talks bodies too — `Chain.priority(Body, …)` and
`Chain.tick(Body, …)` — but the chains themselves still read the local player,
the client level and the client-only helpers through `Minecraft`, so each one
downcasts at the top of both methods via `Chain.clientOf(body)`. That returns null
for any body that is not the client's, which is also what the headless matrix
scenes pass when they tick the scheduler with no body. Whether the reflex layer
should run over a server body at all is an open question in the body-abstraction
design; until it is decided, this is the seam it will land on.

## 4. `Walker` — executing a path

Consumes a path and drives the body along it, reporting `Step.WALKING` /
`ARRIVED` / `FAILED`.

Two things surprise contributors:

- **Unplanned falls are not the Walker's job.** Water-bucket clutch handling
  lives in the always-on `ClutchController`, so a body that falls self-rescues
  whether or not a Walker is driving. The Walker only *arms* a planned fall as it
  steps off a lip, and biases the step-off keys so the body drops near-vertically.
- **Its static counters are monotone, never reset.** `Walker.lastStats`
  (`PathStats`) and counters like `descentHolds` accumulate process-wide; a
  caller that wants a window takes a **difference**. They exist so a test can
  distinguish "the guard cost nothing" from "the guard never ran" — readings that
  look identical from outside and want opposite conclusions.

Walker's per-tick work is split into `WalkerTick*` phase classes. That
decomposition has its own document —
[`walker-tick-architecture.md`](../walker-tick-architecture.md) — including the
measured inter-phase data flow. Read it before touching a phase; do not
re-derive it here.

## 5. `PathFinder` — A* over a move catalog

Single-threaded and single-shot: `findPath` runs to completion, and **the caller
is responsible for invoking it off-thread** if the path may be large.

It terminates on goal, node budget (`DEFAULT_MAX_NODES`), wall-clock budget
(`DEFAULT_MAX_MS`), or an empty open set — and in every non-goal case returns a
**best-effort segment** rather than nothing. That matters because Minecraft
worlds are mostly unreachable in some direction, and progress beats standing
still. The fallback uses Baritone-style **incremental cost backoff**: rather than
returning the lowest-h node (which may have wandered far to shave a hair off the
heuristic), it tracks the best node under several g-vs-h weightings and commits
to the most conservative one that still travels a minimum distance. Repeated
calls from successive segment ends give long-distance splicing for free.

**The three extension families**, which is what a contributor actually touches:

| Package | What it is |
|---|---|
| `pathfinder/moves/` | One file per concrete `Move` — an A* edge with a cost and its world preconditions |
| `pathfinder/constraints/` | Hard validity gates — a move is legal or it is not |
| `pathfinder/modifiers/` | `CostModifier`s — additive taxes that steer without forbidding |

Costs are in **1/10-tick units** (~10 per tick of expected travel), and `Move`
owns the shared constants plus the `ALL` catalog assembled from its subclasses.
Prefer a `CostModifier` over a new hard constraint: a tax steers the search,
while a gate can make a region unreachable and turn a slow path into no path.

## 6. The `Body` seam

`Walker` and every process drive a **`Body`** (package `bot/body/`; it was
`movement/Avatar` until 2026-09-14), not a player. Its `entity()` is a
`LivingEntity`: everything the walker reads (position, ground contact, water,
velocity, bounding box, health) and every pose it sets lives there, on a client
`LocalPlayer`, a server `ServerPlayer` and a driven mob alike, so those stay
byte-identical across bodies. The interface itself carries only what every body
has — locomotion impulse and the look.

What only a body with an inventory has sits behind two optionals: **`Hands`**
(hold, place, break, swing, use) and **`Containers`** (recipe book, container
clicks, closing). A process that needs them asks at the top of its tick and, when
the answer is empty, stamps its slot's `lastError` with `BodyReady.Reason.NO_HANDS`
and finishes; a caller that never asks cannot compile a call to them. `asPlayer()`
is the raw `Player` view for the few readers of a player's own state (food,
abilities, the attack cooldown) and is null for a body that is not one. The
walker, which cannot refuse an order, drives `WalkerNoHands` for a handless body:
its dig and place gates fall closed on their own readings.

`ClientPlayerBody` and `ServerPlayerBody` implement all three interfaces, so
a caller holding either concrete type is unchanged. This is what lets the same
process run on a client body and headless on a server tick, and what the
testmod's NPC body (`LivingBody` over a driven piglin) plugs into.

**Naming a body.** `bot/body/BodyRegistry` holds the bodies the API can address
besides the client's own: `/worlddriver server spawn <name>` registers
`player:<name>` (`bot/sim/ServerBodyHost` over a `ServerWorldDriver`), the testmod
registers `npc:<name>` (`NpcBodyHost` over a `LivingBody`), and the registry
empties when the server stops. `mc.bot.goto`, `mc.bot.cancel` and `mc.bot.status`
take `body`; anything but `self` goes to `api/BodyRoutes`, which hops to the
server thread, refuses in `BodyReady.Reason` words judged on the entity, and
hands an `IntentProcess` to the host. A host runs one process on the server tick
(`ServerAvatarManager` ticks any `BodyDriver`) with no scheduler, chains or
reflexes; `self` keeps all three. `BodyRoutes` must not name a client class: on a
dedicated server it is the only `mc.bot.*` code that runs.

⛔ **`bot/sim/**` and the fidelity boundary belong to the parity role.** How
faithfully a `FakePlayer` reproduces a real player — and every known divergence —
is tabulated in [`docs/fake-player-parity.md`](../fake-player-parity.md). Read it
before assuming a vanilla behaviour survives the seam; do not duplicate its
claims here.

## 7. Settings

Two classes, one surface:

- **`BotConfig`** — mutable `volatile` statics, the knobs themselves. Common- and
  server-safe, so a dedicated-server scene can read it.
- **`SettingsRegistry`** — the single source of truth for the `mc.bot.setting`
  key surface: the read snapshot, the MCP schema, and the write path all derive
  from it, and apply *rejects* an unrecognised key.

It exists because that knowledge once lived in three places that drifted, and a
newly declared flag whose schema and write branch lagged was **silently dropped
by the apply path** — accepted, echoed back as set, and doing nothing. The
reflective completion pass by which a new flag enters the surface is a single
method consumed by both the registry and the snapshot, so they cannot diverge,
and a class-load self-check throws on a misspelled field name.

⚠️ **The fourth side.** All of that keeps the key, snapshot, schema and docs
agreeing about which knobs *exist*. **None of them asks whether a knob does
anything.** A field added to `BotConfig` surfaces automatically — so a flag whose
read site never landed, or whose behaviour was later refactored away leaving the
knob behind, is accepted and echoed as set while changing nothing, and the
caller (often an LLM) gets every signal that it worked. `SettingsConsumerTest`
in `common/src/test` closes that side. **If you delete a behaviour, delete its
flag in the same change.**

## 8. The source budget

No Java file may exceed **3000 lines**; the gate is
`scripts/check_source_budget.py`.

⚠️ **`Walker.java` and `BotConfig.java` both sit *at* the cap** — within a line or
two of it. Adding to either means **extracting first**; there is no headroom to
spend, and shaving comments to buy a few lines trades readability for a number
the gate cannot tell apart from real work.

The `WalkerTick*` phase classes are the reference pattern for carving up a large
sequential method without semantic drift. Follow that, not a fresh invention.

---

## The rest of the packages

| Package | What it is |
|---|---|
| `body/` | `Body`, `Hands`, `Containers`, `BodyCapabilities` and the client implementation `ClientPlayerBody` |
| `movement/` | `Walker`, the `WalkerTick*` phases, the look and input controllers |
| `pathfinder/` | A*, the move catalog, constraints, cost modifiers |
| `process/` | One class per verb behaviour |
| `scheduler/` | `ProcessScheduler`, `Chain` and the priority ladder |
| `world/` | The bot's world model — `WorldView` implementations, hazard fields, scene model, survival facts |
| `sim/` | Server-side body equivalence — **parity role's territory** |
| `debug/` | Path archive/replay, chart rendering, probe tools — instrumentation, strippable |
| `combat/`, `elytra/`, `auto/` | Domain helpers behind the matching processes |
| `util/` | Shared helpers (`BlockMatch`, `BotInteract`, …) |

`FocusPolicy` / `MouseYield` govern how the bot shares keyboard and mouse with a
human at the same client — see the input-sharing section of
[`AGENTS.md`](../../AGENTS.md) before changing them.

---

## Before you push

Behaviour here is judged by StageWright scenes, not unit tests. Do not run gates
speculatively — they are slow and the tree is shared. Two rules bite hardest in
this subsystem:

- **A new scene must be registered and listed in `expected-scenes-*.txt` in the
  same change**, or the run prints `UNDECLARED:` and goes red above the
  `VERDICT:` line.
- **`[walker]` / `[expect]` log formats are a contract.** Several dev tools
  recover bot state by regexing them, and a regex that stops matching does not
  raise — it returns nothing, which reads as "no problem found".
