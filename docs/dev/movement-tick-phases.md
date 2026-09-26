# The movement tick phases

`Walker` decides what the bot does on every tick. That decision was once a single method
several thousand lines long. It is now a fixed pipeline of phase classes, each in its own
file under `common/src/main/java/net/magicterra/worlddriver/bot/movement/`, driven from
`Walker#tickInner`.

Read this before changing a phase, and read [`bot-layering.md`](bot-layering.md) first if
the walker's place in the wider subsystem is not already familiar.

## Why the decomposition exists

`scripts/check_source_budget.py` caps two units of hand-written Java. A file may not
exceed its line limit, and a method may not exceed its line limit unless it appears on the
script's grandfather list, in which case the recorded length is a ceiling it may shrink
below but never rise above. Read the script for the current numbers rather than trusting a
figure quoted anywhere else.

The file cap came first and measured the wrong unit. Splitting the walker satisfied it by
moving text: the enormous method survived, redistributed across new files. The method cap
exists because of that, and five `WalkerTick*` entries on the grandfather list are the
backlog it was written to shrink. Deleting an entry from that list is progress that cannot
silently regress; adding one, or raising one, should be a deliberate reviewed change.

The pipeline is also the reference pattern for carving up any other large sequential
method in this repository. Follow it rather than inventing a new shape.

## The pipeline

`Walker#tickInner` constructs a fresh `WalkerTickCtx` and calls the phases in a fixed
order. Each phase returns a `Step` to terminate the tick, or null to fall through to the
next:

1. `WalkerTickPrelude`
2. `WalkerTickStallDetect`
3. `WalkerTickRepath`
4. `WalkerTickSearch`
5. `WalkerTickProgress`
6. `WalkerTickClimb`
7. `WalkerTickEdgeGuards`
8. `WalkerTickAim`
9. `WalkerTickDrive`

`WalkerTickDrive` is the terminal phase and always returns a verdict.

`tickInner` is not the whole tick. `Walker#tick` wraps it so that the safety guards run
after *every* decision path rather than only the paths that reach the end of the pipeline:
the earlier arrangement appended an invariant to the tail of a method with dozens of early
returns, and a fatal step came from a branch that never reached it.

## What crosses a phase boundary

`WalkerTickCtx` is the per-tick handoff. A fresh instance is built every tick and nothing
in it survives the tick; cross-tick state stays in `Walker` fields and in the typed state
holders those fields are grouped into. The context is organised by the phase that
*produces* each group:

| Group | Produced by | What it carries |
|---|---|---|
| `frame` | Prelude | The resolved entity being driven and the anchor cells every later phase keys off. Phases that move the entity re-derive the foot cell. |
| `stall` | StallDetect | The stall and deviation verdict the repath and search stages consume. |
| `edges` | Climb and EdgeGuards | The committed edge under execution and its guard classifications, consumed by Aim and Drive. |
| `aim` | Aim | The full aim, camera and drive-shaping plan that Drive executes. |

Read the field lists in `WalkerTickCtx` rather than reproducing them; they move. What does
not move is the contract: a phase writes its own product group and only re-publishes
`frame` basics it recomputed, and a phase's rehydrate block names exactly what it
consumes.

## The contract is enforced, not merely documented

`common/src/test/java/net/magicterra/worlddriver/bot/movement/WalkerTickDataflowTest`
re-derives the data flow from source on every `:common:test` run and fails when it stops
being strictly forward. It reads the phase order out of `Walker#tickInner` rather than
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

`WalkerTickProgress` can replace the whole path and fall through on the new one. Almost
everything downstream re-derives after the swap: the committed edge comes from the
walker's own lookup in Climb and the waypoint from EdgeGuards, both after the replacement,
and the foot cell stays valid because no phase moves the bot — the position only changes
at the physics step after the pipeline.

The exception is the breaking-edge flag in the stall group. StallDetect computes it from
the *old* path's edge, and Drive reads it alongside the *new* edge. It feeds one watchdog
exemption, for one tick, at the start of a fresh episode. It is left alone deliberately:
`WalkerTickDrive` is state-machine surgery, and this is a one-boolean discrepancy with no
observed consequence. It is written down here so that it stays a known quantity rather
than being rediscovered.
