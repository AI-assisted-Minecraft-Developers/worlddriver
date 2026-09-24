# Getting evidence out of a long traversal

## The problem

The failures that matter over a long journey are failures of *execution*, not of the plan:
churning in deep water, sliding off a sheer face, wedging in a one-block gap, orbiting a goal
without reaching it. Numeric path statistics and a text log cannot answer the questions those
raise — did the bot actually follow the plan, did its heading drift from the target, was its
speed right for the medium it was in, and where exactly did it come apart.

Worse, they were not reproducible. Every search varies from run to run, so "run it again and
watch" is not a method, and the only artefacts left behind were a screenshot and a log.

Two mechanisms answer those two halves: a chart that shows what one traversal did, and an archive
that lets the same traversal be run again.

## The observation seam

Both sit behind one inert seam in the pathfinder core: an interface, plus a static holder whose
default is a no-op. The core never names any debug class, so the debug package can be examined,
replaced or reasoned about independently, and with the sink at its default the virtual calls have
empty bodies that the just-in-time compiler removes.

> The original design claimed the whole debug package could be **deleted** and the core would still
> compile. That is no longer true: the movement core and the replay installer now name classes in it
> directly. The seam still keeps debug code out of the search's own logic, which is what it is for,
> but the strippability claim should not be repeated.

## Charts: what one traversal did

Capture is per journey and is reset only when a **new goal begins** — never on failure and never on
cancel, because the failure is the thing worth keeping. Losing a session because it ended badly
would discard exactly the runs that are worth looking at.

Candidate nodes are reservoir-sampled down to a cap, so the spatial spread of the search survives
the limit rather than the first N expansions being kept and the rest thrown away. The executed
trajectory is a ring buffer.

Rendering is a pure function from an immutable session snapshot to an image. That keeps it testable
with no game and no file system, and it keeps every pixel of work off the game thread. The captured
target heading is the value the walker actually aimed at *that tick*, taken after the target is
computed and before the inputs are written — a value sampled anywhere else would be answering a
different question.

Delivery is a PNG on disk whose path the call returns, so the result can be opened and looked at
rather than parsed. The speed panel draws reference lines at roughly 5.6 and 4.3 blocks per second
for sprinting and walking, so a speed trace can be read against what the medium should allow without
anyone having to remember the numbers.

Drawing inside the game's own process is safe because the game already uses the same imaging library
for screenshots.

This deliberately is not an in-world overlay, is not streamed live, and does not use a charting
library.

## Archives: running the same traversal again

An archive is one self-contained file per journey holding three things: the planned nodes and edges
per segment, a **sparse** envelope of the blocks around the route, and the per-tick executed
trajectory.

The envelope is a thin corridor around the route's nodes rather than a dense box, because routes are
corridors and a dense capture of a few hundred blocks of travel is unusable.

Pose is never predicted from terrain. What pose the player ends up in depends on intent that only the
executor has, so the archive records what the terrain **allows or forces** — whether a standing box
fits, whether a crouching one does, whether the ceiling forces a crouch, whether the foot cell is
hazardous, how much the bounding box overlaps — and separately records the pose actually observed
each tick. The two together are what make a disagreement visible.

Those facts are computed against vanilla's own pose dimensions: standing is 0.6 by 1.8, crouching is
0.6 by 1.5, and crawling is 0.6 by 0.6; a pose fits when the level reports no collision for that box
slightly deflated. Two consequences are easy to get wrong and are worth stating: the swimming pose
requires sprinting as well as being in water, so merely floating is not swimming, and crouching
requires the sneak input rather than happening automatically under a low ceiling.

Replaying force-restores the envelope, teleports to the recorded start, and re-executes through the
real walker with real physics.

The original design made verbatim re-execution the only mode, with replanning disabled, so that a wedge
would be **recorded rather than routed around**. That mode still exists and is still what makes an
archive an oracle rather than a demonstration. It is no longer the default: the replay call now replans
from the recorded goal unless told otherwise, because the regression corpus wants a faithful re-run of
the *decision*, not of one recorded path.

Analysis is a standalone script that recomputes no game physics at all. The mod reduces every physics
question to a boolean at record time, which is what keeps the offline reader honest — it cannot quietly
disagree with the game, because it never asks the game anything.

## What this rules out

Neither mechanism may affect what the executor does. The trace seam is inert by default and the recorder
only observes; an observation that changed the run would make every archive a record of a different
system.

The analysis script may not grow its own physics model. The moment it computes rather than reads, the
archive stops being evidence.

## Where to look

- `bot/pathfinder/PathTrace.java` and `PathTraceHolder.java` — the seam and its no-op default.
- `bot/debug/PathDebugRecorder.java` — sampling and session lifetime.
- `bot/debug/PathChartRenderer.java` and `PathChartWriter.java` — the pure render and the file.
- `bot/debug/PathArchive.java`, `PathArchiveRecorder.java`, `NodePhysics.java` — the archive format and the
  physics facts.
- `bot/debug/ReplayTool.java`, `bot/ReplayInstaller.java` — restoring and re-executing.
- `bot/debug/DebugTools.java` — the two verbs and their parameters.
- `path-replay/` — the offline analyser.
- `bot/BotConfig.java` — the capture flags and their caps.

What the corpus of archives is used for, and the rule for accepting a change against it, is
`docs/design/conformance-and-corpus-gating.md`.
