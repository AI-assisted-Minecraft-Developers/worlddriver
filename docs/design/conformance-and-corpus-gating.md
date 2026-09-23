# Two world models, and how a fix is accepted

## The problem

The bot carries two independent models of the world. The planner has a cost model that decides
which cells are walkable and which edges exist. The executor has real physics, which decides what
the body can actually do. They diverge, and every divergence looks the same from outside: the bot
gets stuck.

Concretely, the planner emitted two-block climbs a jump cannot make, one-block bank climbs a
buoyant body cannot mount, and vine climbs with no check that the body could hold on.

The gap being addressed is not any one of those. It is that there was no mechanism to **find**
them. The scene suite of the time was tautological: it asserted that a path exists under the
planner's own cost model, which is the planner agreeing with itself. A suite like that can be
entirely green over a bot that cannot walk.

## What was decided

### Two discovery layers, for the two kinds of divergence

**Per-move conformance** catches a planner predicate that is looser than the body's real
capability. For each move type, stage the geometry the planner says it can handle and drive the
body through it.

A move must be tested from **several starting states** — grounded, floating, mis-landed — because
the starting state, not the move, is very often what decides whether it works. A move that succeeds
from a clean standing start and fails from a half-landed one is exactly the case that produces a
stuck bot in the field and a green suite at home.

**Journey replay** catches executor fragility, and guards against regressions. An archived journey
is re-run and the stall behaviour compared; see `docs/design/observing-the-executor.md` for what an
archive contains and how a replay works.

### Scenes are cut from real routes, never invented

A hand-built arena encodes the author's assumption about the geometry, and that assumption is
usually the same assumption the planner is making — which is how this project repeatedly got a green
suite over a broken game. Staging is therefore cut from an envelope a real journey actually passed
through.

### One acceptance rule, and it is whole-corpus

A candidate change is accepted only if, combined with the changes already accepted, it is run across
the **entire** corpus and total stall time falls while no individual archive regresses beyond a
tolerance.

That rule exists because of a measurement, and the measurement is the reason it cannot be relaxed. A
matrix over one set of flags produced, per archive: one archive's stall time going from 579 ticks to
1935 under a flag combination that *improved* a second archive from 840 to 649 and a third from 1814
to 829. **No flag combination won everywhere.** Accepting a change on the strength of one archive
therefore accepts a change that is known to make other terrain worse, which is how a long sequence of
individually justified improvements produces a bot that is worse overall.

The tolerance is ten per cent, and the target for a traversal to read as smooth is under about 120
ticks of stall, which is roughly six seconds.

## Two traps in reading the evidence

Reruns with **identical** flags swing enormously — a factor of four to five per archive has been
observed, root-caused to the interaction of tick-slicing with just-in-time warm-up and genuine
bistability in the search. Any single-run comparison smaller than that is noise. This is the reason
the rule above is stated over the whole corpus and over aggregate stall rather than over one number.

**Peak stall time is confounded by how the run ended.** A run that gives up early and then stands
still scores a low peak, which reads as a good result and is the opposite of one. Peak stall must be
read alongside the terminal reason, never alone.

## Current state

The mechanism exists and the scripts are in the tree, but **the corpus it judges against is not in
version control**, and the tooling's paths have not matched where the archives actually live since the
project was renamed. In other words this gate is designed, implemented, and not currently running.
That is worth knowing before citing a corpus result as evidence for anything.

The log line the telemetry reads is a contract: `docs/dev/testing.md` names the scripts as consumers of the
walker's step line, so its format cannot be changed casually.

## Where to look

- `scripts/pmcs/` — telemetry parsing, per-move conformance, the acceptance gate, and the corpus
  runners.
- `docs/dev/pathfinding-conformance.md` — how to run it.
- `docs/dev/replay-corpus.md` — the living record of what has been measured, including the lever
  matrix summarised above and the rerun-variance investigation.
- `docs/design/observing-the-executor.md` — the archive format the corpus is made of.
