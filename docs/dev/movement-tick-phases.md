# The movement tick phases

`Navigator` decides what the bot does on every tick. That decision was once a single method
several thousand lines long. It is now a fixed pipeline of phase classes, each in its own
file under `common/src/main/java/net/magicterra/worlddriver/bot/control/`, driven from
`Navigator#tickInner`.

The package is split by what each part does:

| Package | Holds |
|---|---|
| `control` | `Navigator` and its outward face, the per-tick context, constants, geometry, telemetry |
| `control.sense` | Prelude and StallDetect: what the tick starts from |
| `control.route` | The plan: `Route`, adoption, stub routes, Repath, Search, Progress, path smoothing |
| `control.controller` | `Controller`, `Channel`, `Arbiter` and the controllers |
| `control.steer` | The regular walk: EdgeGuards, Aim, Drive |
| `control.act` | `InputFrame`, `Safety`, `Actuate` |
| `control.medium` | The walk of a body that swims or flies |

Members another subpackage needs are public; that is the price of the split. Nothing outside
`bot/control` is meant to use them, though only review keeps the layers above from doing so.

Read this before changing a phase, and read [`bot-layering.md`](bot-layering.md) first if
the navigator's place in the wider subsystem is not already familiar.

## Why the decomposition exists

Checkstyle's JavaNCSS check caps how many statements a method, a class and a file may hold
([`code-style.md`](code-style.md)). Comments do not count.

A cap on file size alone measured the wrong unit. Splitting the navigator satisfied it by
moving text: the enormous method survived, redistributed across new files. The method limit
is why; the `*Phase.run` methods it still reports are the backlog it is there to shrink.

The pipeline is also the reference pattern for carving up any other large sequential
method in this repository. Follow it rather than inventing a new shape.

## The pipeline

Each phase is an object the navigator holds in a field (`prelude`, `stallDetect`, `repath`,
`search`, `progress`, `edgeGuards`, `aim`, `drive`). `Navigator#tickInner` constructs a fresh
`TickCtx` and calls the phases in a fixed order. Each phase returns a `TickStep` to
terminate the tick, or null to fall through to the next:

1. `PreludePhase`
2. `StallDetectPhase`
3. `RepathPhase`
4. `SearchPhase`
5. `ProgressPhase`
6. `EdgeGuardsPhase`
7. `AimPhase`
8. `DrivePhase`

`DrivePhase` is the terminal phase and always returns a verdict.

After Progress, once the route is settled for the tick, `Arbiter` runs the controllers. A
controller (`Controller`) takes some of the tick's four channels (`Channel`: move, look,
jump, hands) for a few ticks: it starts when its condition holds, drives its channels every
tick it runs, and stops when it cannot go on, has run its longest, or a higher-priority
controller needs one of its channels. A controller can also find mid-tick that it has
nothing to do and let go, and the next one down gets its channels the same tick. Each
channel goes to the highest-priority running controller that claims it. The phases only ask
a controller for something; they do not drive the body themselves.

| Priority | Controller | Channels | What it does |
|---|---|---|---|
| 1 | `DrownEscape` | all | Surfaces for air when the air runs low under water |
| 2 | `Recovery` | move, look, jump | Backs off a riser the bot keeps failing to mount; pushes it out of a wedge or a churn pocket |
| 3 | `WaterClimb` | all | Gets a floating bot onto a bank: a foothold from the hand, or a notch dug into the bank |
| 4 | `Pillar` | all | Jumps and places under itself: a planned pillar, the settle onto it, and the climb back up to a route the bot fell below |
| 5 | `ParkourPlace` | all | A leap that places its own landing block mid-air |
| 6 | `VineClimb` | move, look, jump | Climbs or slides down a vine the bot hangs on |
| 7 | `RouteDig` | look, hands | Breaks and places the blocks the current edge needs |

While a controller holds the move channel the rest of the pipeline is skipped for that tick.
The regular walk (Aim and Drive) cannot yet run without the look, so while `RouteDig` holds
it the walk only decides how to stand (`StandToWork`): still, pressing into a bank it is
breaking from the water, or sneaking at a lip it is placing below. With no controller on
the hands, the dig key is released. `NavigatorInspector#channels` prints who held which
channel and why each idle controller did not start.

`tickInner` is not the whole tick. `Navigator#tick` wraps it so that the safety guards run
after *every* decision path rather than only the paths that reach the end of the pipeline:
the earlier arrangement appended an invariant to the tail of a method with dozens of early
returns, and a fatal step came from a branch that never reached it.

## What crosses a phase boundary

`TickCtx` is the per-tick handoff. A fresh instance is built every tick and nothing
in it survives the tick. State that does survive lives with the phase that keeps it: the
step-progress clocks and the arc shadow in `progress`, the churn window and the escalation
clock in `stallDetect`, the sprint brakes in `drive`, and so on. Another phase reads it
through the owner (`nav.progress.stepProg`). Each phase's `reset(ResetScope)` clears what it
keeps when the walk gets a new goal or a replan. Only what belongs to the whole walk, such as
the stall counter, the route and the error, stays on `Navigator`.

The context is organised by the phase that *produces* each group:

| Group | Produced by | What it carries |
|---|---|---|
| `frame` | Prelude | The resolved entity being driven and the anchor cells every later phase keys off. Phases that move the entity re-derive the foot cell. |
| `stall` | StallDetect | The stall and deviation verdict the repath and search stages consume. |
| `edges` | EdgeGuards | The committed edge under execution and its guard classifications, consumed by Aim and Drive. |
| `aim` | Aim | The full aim, camera and drive-shaping plan that Drive executes. |

Read the field lists in `TickCtx` rather than reproducing them; they move. What does
not move is the contract: a phase writes its own product group and only re-publishes
`frame` basics it recomputed, and a phase's rehydrate block names exactly what it
consumes.

## The contract is enforced, not merely documented

`common/src/test/java/net/magicterra/worlddriver/bot/control/PhaseDataflowTest`
re-derives the data flow from source on every `:common:test` run and fails when it stops
being strictly forward. It reads the phase order out of `Navigator#tickInner` rather than
hardcoding it, so reordering the pipeline re-checks every field against the new order
instead of quietly invalidating the test. It scans source rather than running the
pipeline, because the pipeline needs a live `Level` and the ordering property is a static
property of the code.

Four things fail it, in descending order of how much they cost:

- **A read before the write.** A phase reading a product an earlier phase never wrote gets
  the zero value on every tick, forever. There is no exception and no log entry — just a
  guard that never fires. This is the expensive one, and the reason the test exists.
- **A context field no phase writes**, so every reader gets the zero value.
- **A context field no later phase reads**, which should be a local in its producer rather
  than a context field.
- **The phase files on disk and the phases `tickInner` calls disagreeing**, which means a
  phase is dead or is being invoked from somewhere outside the single pipeline the other
  assertions rest on.

The test deliberately does not assert that a field has exactly one writer, because the
context permits a phase to re-publish `frame` basics it recomputed. Read-before-write is
keyed off the first writer in driver order, which stays correct when that happens.

## The one value that crosses a mid-pipeline path swap

`ProgressPhase` can replace the whole path and fall through on the new one. Almost
everything downstream re-derives after the swap: the committed edge and the waypoint both
come from the navigator's own lookup in EdgeGuards, after the replacement,
and the foot cell stays valid because no phase moves the bot — the position only changes
at the physics step after the pipeline.

The exception is the breaking-edge flag in the stall group. StallDetect computes it from
the *old* path's edge, and Drive reads it alongside the *new* edge. It feeds one watchdog
exemption, for one tick, at the start of a fresh episode. It is left alone deliberately:
`DrivePhase` is state-machine surgery, and this is a one-boolean discrepancy with no
observed consequence. It is written down here so that it stays a known quantity rather
than being rediscovered.
