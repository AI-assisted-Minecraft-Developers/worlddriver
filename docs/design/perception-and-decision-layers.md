# Perception, and which layer a decision belongs to

## The problem

Two failures kept recurring in unassisted survival runs, and they had the same shape.

Decisions were being taken at the wrong layer. Dodging and fleeing were being driven by an
external language model at model latency while hostile mobs deal two to four points of damage
per second. That is not a tuning problem; it is an arithmetic one, and it killed the bot
repeatedly.

Perception was being assembled per decision out of several separate reads, and the server's
view and the client reflexes' view could disagree. When they did, the thing the caller reasoned
about was not the thing the reflexes acted on.

A third failure sat between them: fleeing maximises distance, and a soft danger cost at the edge
of a cliff loses to "further away". A low-health, unarmoured bot fled off cliffs and into deep
water.

## What was decided

### One blackboard, computed once per tick

`bot/world/WorldModel` runs at the top of the client tick and publishes an immutable snapshot to a
volatile field, the same off-thread pattern the bot state and the scheduler already used. It answers
only what is true nearby — the hazard grid, threats, light and time of day, which neighbouring cells
are standable — and takes no decisions and has no side effects.

Everything else reads that one object: the reflex chains, the pathfinder through the world view's
danger cost, and the caller through the scene observation. Because the client-side scene read is
forced to come from the client's own model, what a caller sees and what the reflexes avoid are the
same computation at the same instant. A disagreement between perception and action is not unlikely,
it is unrepresentable — which was the point, because that disagreement was the defect.

Edge-triggered events such as "exposed at nightfall" and "cornered" are emitted by the caller of the
model, not by the model, so the blackboard stays free of side effects.

The hazard mathematics is a pure function of a world view plus scalars, which is what lets it be
asserted on a dedicated server with no client at all.

### One question decides the layer

> Can the correct answer be computed from local state within a tick? Then it is a reflex, in
> algorithmic code, and it never consults a language model.
>
> Is it a bounded procedure with a clear success test? Then it is a process — a skill, closing over
> a few seconds.
>
> Does it require judgement, a goal, world knowledge, or disambiguation? Then it belongs to the
> caller, driven by events, and never inside a tick.

Dodging a blast, not stepping into a lethal cell while fleeing, escaping suffocation, swimming for
shore, and retreating at low health are all reflexes. Walking, mining, crafting and bunkering are
processes. What to build, where to make a base, whether to fight or withdraw, and how to read chat
are the caller's.

The same rule stated from the survival side: **the loop that keeps the bot alive never goes through
the model.** A creeper's fuse, a skeleton's firing interval and the attack-cooldown recovery are all
measured in ticks, and any perceive-then-react round trip through a model is fatal by construction.
The model sets intent — fight that, withdraw, use this playbook — and tick-level tactics stay local.
Threat reflexes do not even need an intent; they bid continuously and take over when a threat appears.

### Lethal cells are expensive, never impossible

Danger is injected into the pathfinder as a large but **finite** penalty. It is never infinite and
never a hard prune, so a route through a dangerous corridor stays feasible and a search can never
deadlock for want of a legal successor. The penalty is health-aware: survivable fall distance is
recomputed from current health on each replan, so the same ledge is priced differently by a healthy
bot and a hurt one.

Deep water is exempt from the lethal treatment and priced by its own tax instead; charging it the
lethal penalty sent every crossing hugging the nearest shore. See `docs/design/water-and-swimming.md`.

### Being cornered is reported, not acted on

When no improving non-lethal step exists the bot stops at the safest reachable cell and reports the
situation. It does not dig itself a shelter. An autonomous bunkering reflex had already been built once
and withdrawn as too unpredictable, and sealing yourself in while surrounded by melee attackers is a
known way to die. The caller decides whether to bunker, fight, or build upwards.

## What this rules out

A caller cannot take a reactive decision, because by the time it could, the decision is stale. It can
only change the conditions the reflexes and the planner operate under, which is what
`docs/design/route-selection.md` exists to make expressible.

A reflex cannot ask a question it cannot answer from the snapshot, which is why every gate in
`bot/auto/` is written as a pure function of readings.

## Where to look

- `bot/world/WorldModel.java` — the blackboard and its snapshot.
- `bot/world/HazardField.java` and `HazardCell.java` — the finite lethal penalty.
- `bot/world/SurvivalMath.java` and `SurvivalFacts.java` — the pure survival arithmetic.
- `bot/world/AsciiMapRenderer.java` — the map the caller reads.
- `bot/scheduler/` — the reflex chains that consume it; see `docs/design/scheduler-semantics.md`.
- `api/ObserveApi.java` and the client scene read — the caller's view.

Two decisions recorded here were later revised, and the revisions live in
`docs/design/scheduler-semantics.md`: shelter-seeking gained a tier that *can* preempt a user task, and
the scene query parameters landed on the server-side observation rather than the client one.
