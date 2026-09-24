# Navigation as an intent, not as a verb per scenario

## The problem

Every new way of wanting the bot to move used to arrive as a new Java process, and each one
hand-wired the same five concerns: a goal, a soft preference about which cells are nicer, hard
rules about which cells are forbidden, a rule for when to stop, and a policy for when to replan.
Adding a way to move meant adding engine code, so the surface could never keep up with what a
caller might reasonably want.

The processes also froze their goal. The walking process held a final goal set in its constructor,
so changing anything mid-journey meant cancelling and starting again — and cancelling a walk in
water is a known way to drown.

## What was decided

### Factor the five concerns into orthogonal primitives and have one process interpret them

- **A goal** is a predicate over cells, with a composite form meaning "any of these will do".
- **A soft preference** is a `CostModifier`, consulted per edge, which may only **add** cost.
  Subtracting would let the straight-line heuristic overestimate and A* would stop returning
  optimal paths.
- **A hard rule** is a `Constraint`, evaluated per edge in the neighbour loop, pruning successors.
- **What the bot is allowed to do** is a capability profile, checked against each move type's
  declared requirement.
- **When to stop and when to replan** belong to the process and the walker.

The first three, plus the capability profile, are bundled into one `SearchProfile` value that is
threaded through a search.

`IntentProcess` interprets all of it and is the single execution body behind `mc.bot.goto`. It
replaced the per-scenario processes rather than joining them.

### Capability and constraint are two mechanisms, on purpose

A capability gates whole **move types** at the catalogue level: each move declares what it requires,
and a profile that does not grant it removes that move from consideration everywhere. A constraint
prunes individual **edges** by position. They look similar and are not interchangeable: "this bot
cannot parkour" is a statement about the move catalogue, while "do not go into that box" is a
statement about geometry. Conflating them would mean either expressing a geometric rule as a move
type or re-checking a limitation of the bot at every cell.

This generalised what had previously been an ad-hoc boolean for suppressing block placement.

### The intent is mutable; the goal list is ordered

The intent holds an ordered list of targets — the waypoints in order, then the final goal — and the
process advances through them. Changing conditions mid-flight is an amendment plus a forced replan,
not a cancel and re-attach, precisely because tearing down and restarting a journey has its own
failure modes.

An intent that is anchored to a moving entity re-solves its anchor when the entity has moved past a
threshold, subject to a minimum interval, and forces one replan. The modifiers themselves stay
immutable; the re-solve is the only dynamic seam, and it is deliberately outside the search rather
than inside it, so that a search remains a pure function of its inputs.

An anchor entity that cannot be found is tolerated rather than fatal.

### Soft and hard stay separate channels

A hard rule can make a search infeasible where a penalty only makes it expensive, so the caller has
to be able to say which it means. That distinction is the reason every route condition described in
`docs/design/route-selection.md` comes in both strengths.

Hard rules that can be violated at the start of a search all carry a rejoin rule, because otherwise
a bot pushed into a forbidden region has no legal outgoing edge at all. The distance-based ones
measure to the **cell centre**, so that the soft and hard forms of the same rule measure the same
geometry and do not disagree at the boundary.

### The taxes were extracted before anything new was layered on

The pathfinder's built-in water, submersion, climb-out and descent taxes were pulled out behind the
same cost-modifier interface as a strictly behaviour-preserving change, gated on producing identical
replans, before any new modifier was added. The point was to be able to prove that the composable
stack and the old fixed one are the same thing.

Cost modifiers are accounted for by name during a search, and the totals can be explained along the
final route or across the whole expansion. That accounting is what the detour event names its culprit
from.

Floating-point addition is not associative, so the order in which taxes are summed is fixed and the
bias modifiers are appended after the legacy taxes. Changing the order changes results.

## What this rules out

There is no verb per navigation scenario, and there is no single generic navigation verb either — the
original plan was one — because `mc.bot.goto` already was the walking verb and adding a second one
would have doubled the schema cost for nothing. Following shares the same condition parsing, so the
two cannot drift.

A preference cannot be expressed as a prune and a prohibition cannot be expressed as a penalty. That is
a deliberate constraint on callers, not an oversight.

## Where to look

- `bot/process/Intent.java` and `IntentProcess.java` — the value and the process that interprets it.
- `bot/pathfinder/SearchProfile.java` — the bundle of bias, capability and constraints.
- `bot/pathfinder/CostModifier.java` and `Constraint.java` — the two channels, and their contracts.
- `bot/pathfinder/modifiers/` and `bot/pathfinder/constraints/` — the implementations.
- `bot/pathfinder/PathFinder.java` — where taxes are summed, and the note on ordering.
- `bot/RouteParams.java` — the pure parser that turns a caller's route object into a profile.
- `bot/GotoGoalResolver.java` — goal selection only; everything else is the parser's.

An escalation channel from the engine back to the caller was designed and never built; the proposal is
in `docs/archive/intent-supervisor-proposal.md`. Its motivating failures were addressed instead by the
honest terminal reporting in `docs/design/scheduler-semantics.md` and the route events in
`docs/design/route-selection.md`.
