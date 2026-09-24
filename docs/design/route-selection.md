# Route selection: the model sets conditions, A* picks cells

> This design has been implemented. The constraints, the cost modifiers, the search scope,
> the preview and the route events all exist under `bot/pathfinder/` and `bot/`, the
> `route` object is the only route surface on `mc.bot.goto` and `mc.bot.follow`, and the
> scene families `wd.route*` and `wd.clientRoute*` hold the behaviour.

## The problem

A language model cannot choose a path cell by cell. The choice has to happen inside a game
tick, tens of thousands of times, and the model is hundreds of milliseconds away. But
"the bot walked straight through a pack of zombies" and "the bot crossed open ground in
front of a skeleton" are exactly the kind of judgement a model should be making, and before
this there was no way for it to express either.

What the model actually needs is four things: a way to say what to avoid and how strongly, a
way to look at a route before committing to it, a way to insist on the route it just looked
at, and a way to be told when the world changed enough that its conditions no longer work.
Close-quarters dodging and blast escapes stay where they were, in the reflex layer, because
they have to answer within one tick.

## What was decided

### One object, and a hard cut

Every route condition lives in a `route` object on `mc.bot.goto`. The top level keeps only
target selection — position, nearest block, entity, direction, waypoint and the selectors'
own radius and distance arguments — plus the usual await parameter.

The old top-level condition fields were removed outright rather than kept as aliases. There
is no compatibility layer: a call using one fails in schema validation with an unexpected-key
error before it reaches the handler, and the migration table lives in the tool description and
in the RPC method reference, which is where someone writing a call is looking anyway.

`mc.bot.follow` was cut in the same change, because it shares the resolver and accepts the
same conditions. Cutting only `goto` would have left `follow` as the compatibility layer that
was being avoided.

The reason for an object rather than a dozen booleans is cost. Every tool's schema is sent in
every prompt to every model client, so surface area is a permanent tax; an object with a handful
of enumerated fields is cheaper than fifteen flags, and it matches how the request is actually
thought about — where I want to go, what I will and will not change, and how frightened I am.

### One snapshot per search, and it carries no entities

Everything new needs to know where the mobs and the watchers are. That data is gathered once per
search into a `SearchScope`, and every component that wants it implements `SearchAware` and is
handed the scope after the cost-modifier list has been finalised, de-duplicated by object identity,
because a component can be both a constraint and a cost modifier and must be told only once.

`SearchScope` holds the start, the goal, a `ThreatSnapshot` — one record per observer, with its
entity id, registered name, eye position, whether it is a ranged attacker, and its effective range
— and a line-of-sight function. It deliberately holds **no entity and no `Body`**. Handing a client
type to a parameter declared as something wider is what stops a class loading on a dedicated
server, and a scope object holding a `Body` would cause exactly that failure. `WorldView` is untouched
and still does not know what a `Level` is.

The scope is supplied to the pathfinder through a function set at the one legitimate construction
point in the walker and at the preview search. The three debug tools and the unit tests do not set
it and get an empty scope: no snapshot, a line-of-sight function that always returns true, and
every new component inert.

The scan box is the start and goal bounding box grown by the largest scan radius any component in
the profile asks for, and each axis is capped, because `Level.getEntities` costs volume and a
hundred-cell search plus a radius easily makes a box of tens of thousands of cells. When the cap
bites, the part nearest the start is kept and the result is flagged as truncated, which the preview
reports verbatim. Mobs in the truncated remainder are reported on the way by the route events rather
than being pretended into the snapshot.

The snapshot does not change during a search, so a search sliced across several ticks stays
self-consistent, and both ends behave the same — a bot on a dedicated server avoids mobs, which it
did not before.

### Hard and soft are two different requests

Every condition comes in both strengths, because "prefer not to" and "must not" are different
instructions. A soft condition is a `CostModifier`, which may only add: A* stays admissible, and a
multiplier below one would let the straight-line heuristic overestimate. A hard condition is a
`Constraint`, evaluated per edge in the neighbour loop, pruning successors.

`MobCluster` and `SightExposure` are both, depending on the mode asked for.

### Counting constraints need a potential as well as a count

A hard constraint can be violated at the moment the search starts — the bot gets pushed into a
forbidden region, surrounded, or knocked out of its corridor — and if every outgoing edge is pruned
the search has no successors at all and the bot freezes. The existing leash and column constraints
solve this with a rejoin rule: while the destination violates the constraint, admit only edges that
reduce the violation, per edge and without state.

That works because both of those measure a continuous scalar. The four constraints added here do not
all have one. Region depth and distance-to-corridor are continuous and copy the rule directly. But
"how many mobs are within the cluster radius of this cell" and "how many observers can see this cell"
are integers, and from the middle of four mobs every neighbour still counts four — strict decrease has
no solution, and the deadlock is back. One cell of movement also rarely changes how many observers can
see you.

So the counting constraints rejoin on two quantities at once: the count must not increase, **and** a
continuous potential — the distance to the nearest mob, or to the nearest observer that can see the
cell — must strictly increase. The potential guarantees progress on every step; the count guarantees
the step is not towards something worse.

The existing rule's own caveat is inherited: in a concave dead end, "closer only" can still stall. That
shows up as a search failure, and the failure names the constraint.

### Line of sight is the one expensive thing, and its budget cannot be a fallback price

Deciding whether a cell is exposed means casting a ray from each observer's eyes to that cell. Results
are cached per cell and per observer for the duration of a search, cells outside an observer's range
count as unexposed, and a search has a hard ray budget.

What happens when that budget runs out is the interesting decision. The obvious answer — price every
remaining cell as exposed — is wrong, and wrong in a way that would not show up for months. It makes a
cell's cost depend on how many cells were expanded before it. A* does not reopen closed nodes, so the
same scene planned twice gives two different routes, and no scene assertion about the result can be
written. A constraint is contractually a pure function of its arguments.

So exhausting the budget aborts the search and re-runs it from scratch without the sight condition, with
a fresh node and time budget, and flags the result. The worst case is searching twice; what it buys is a
result determined only by the cell and the snapshot.

The judgement is geometric only. There is no light level, no sneaking, no invisibility, no per-mob aggro
range, and the ray ignores fluids, matching the threat scanner — so water does not block sight and an
underwater route is scored as more exposed than it really is. Reproducing each mob's perception code is
not worth it.

### The mob half of the danger cost moved out of the client view

Per-mob pricing used to live in `ClientWorldView.dangerCost`, using a snapshot that view built for itself,
so only the client had it. Leaving it there alongside `MobCluster` would have priced mobs twice on the
client and not at all on a server. It was removed: `dangerCost` now covers terrain hazards only, and the
`WorldView` interface is unchanged.

The global mob-avoidance setting survives with a new meaning — it is the default `route.mobs` block, applied
when the caller gives none — so the three numeric settings became the defaults for that block's fields. Both
ends now behave identically, and the bold risk preset can switch per-mob pricing off completely, which was
not expressible before.

### Preview does not go through the user task chain

The chain that owns user tasks cancels the running process as the first thing it does, so a preview started
through it would kill the walk that prompted it — and the whole point is to preview while walking. Preview is
therefore a separate search driven by the client tick, with its own slice budget, touching neither the walker
nor the chain, one at a time with the rest queued.

Preview is asynchronous because it has to be: searches are sliced across ticks on the game thread, and reading
the world off-thread is forbidden. So the call returns immediately and the result arrives through the process
slot mechanism. That required one general change, with a trap in it. The await helper treats a missing slot as
"already finished", and there was no preview slot, so a preview would have reported instant success. Adding the
slot is easy; the rule for finding it is not. It had to be "use the slot named in the response if there is one,
otherwise the literal from the route table" — and **not** "only trust the response", because none of the other
fifteen await sites returns a slot name, so that version would silently drop every other verb's await into the
missing-slot branch. Silently: no error, just an await that stops awaiting. A unit test holds that rule in place.

What comes back is a risk breakdown per segment, split where the risk changes, not eighty-seven coordinates:
which observers see this stretch and for how many cells, how far the nearest mob is, which regions it crosses.
Coordinates are opt-in. The model reads "the first thirty-one cells are clear, the next twenty-one are in one
skeleton's line of sight for nine of them".

A preview costs a whole search budget, and a preview with waypoints costs one per segment between waypoints, and an exhausted ray
budget costs another. The tool description says so, because the natural model behaviour — tighten and preview
again — is priced per round and should converge in two or three.

### Adoption feeds the raw search result, not the pretty one

Executing with a plan identifier hands the cached **unsmoothed** A* result to the walker. The preview *displays*
the straightened route, but adoption must not use it: the adoption path straightens again, trims the tail for
best-effort, and fast-forwards over the prefix already walked, so handing it a straightened route straightens
twice.

Adoption also already anchors to the nearest prefix node, absorbing the drift the bot accumulated while the
preview ran, and already truncates the route at the first edge the current world no longer accepts — which
means a preview whose first segment has gone stale yields an empty route and a refusal, falling through to an
ordinary search, which is the right outcome.

There is deliberately no separate "must be within N cells of the start" knob: adoption has its own distance gate
already, and two knobs governing one thing will disagree eventually.

Adoption guarantees only that the first segment is the previewed one. Replanning happens on the way as it always did.
That guarantee is still worth having, because between two independent searches there are at least three variables
— the bot's position, the decay of stuck penalties, and straightening — so without adoption there is no mechanism
at all that makes preview and execution agree.

### Three events, and only for conditions that were asked for

`route.blocked` fires when the planner can only return a partial route, naming either the constraint that pruned
the most nodes **among the ones this call declared**, or the budget. Restricting attribution to declared constraints
matters: when a goal is walled in, the constraint that prunes most is usually the water rule or a capability filter,
neither of which the caller chose, and reporting those tells the model nothing it can act on.

`route.exposed` fires when a route enters an observer's line of sight that the previous plan did not, comparing
against a summary kept on the intent and dying with it.

`route.detour` fires when a route runs longer than a multiple of the straight-line distance, and names the cost
component contributing most — which is the same calculation the tax explainer already did, extracted so the log
line and the event share one implementation.

All three are debounced per observer and per reason. All three are conditional on the corresponding condition being
present: no sight condition means no `SightExposure` component, which means no exposure event and no rays cast.
Nothing is watched on the model's behalf that the model did not ask for. Ignoring the events is fine; the bot keeps
walking under the conditions it has and the reflexes keep protecting it.

The events do not fire on every replan. Each search takes a fresh snapshot, so the planner tracks moving mobs by
itself; the model is interrupted only when the conditions it set stop producing a good answer.

### Map overlays recompute rather than read the planner's snapshot

The sight and mob-density overlays on the scene observation are computed fresh at the moment of observation, using
the same scanning and the same exposure test. No search is running then, so there is no snapshot to read. Sharing
the *code* rather than the *data* is the point: what the model sees on the map is danger judged the same way the
planner judges it.

## What this rules out

A route condition cannot loosen a global switch, only tighten it. Breaking and placing each have a global gate, and
a `route` field can forbid but never permit. The breaking gate is what it is because of an incident in which a
leashed bot with digging forbidden at the intent level dug its way back underground anyway, and the only thing that
stopped it was the global switch. An intent field that bypassed the global switch would mean there was no global
switch.

Preferring to break cannot be expressed as a discount on breaking, only as a surcharge on not breaking, because the
break price is computed in the world view and multiplied by a global factor that the search profile cannot reach —
and because a multiplier below one breaks admissibility. Only-add, so A* stays optimal.

Flight is a single mode that takes over the whole journey rather than one segment of it. The elytra planner is a separate
three-dimensional search and does not go through A*; mixing a flown segment with a walked one was left until a scene
needs it.

Preview, scoring and adoption belong to the client's player. The `route` object itself reaches any named bot —
`mc.bot.goto` dispatches through `api/BodyRoutes` when a `body` other than `self` is given, and the conditions and
the parser work there — but `plan`, `planId` and waypoints are refused by name on any other bot, because a waypoint
lives in the client's memory and a preview is the client's planner. `route.requireTool` reads an inventory and is
refused on a bot that has none.

## How it is verified

The condition-parsing and constraint layer is pure and runs headless on a dedicated server (`wd.route*`): a scene
hands a route object to the parser, gives the resulting profile to a server-side player, and asserts on the resulting
route. The preview, the plan identifier, the awaits and the events exist only on the client's player, so they run on a
topology with a real client (`wd.clientRoute*`).

Two of those scenes are worth knowing about because of what they had to avoid asserting. The skeleton-sight scene
also asserts that the ray budget was **not** exhausted, because exhaustion drops the sight condition and re-runs, and
a scene that did not check this could go green for the wrong reason. The preview-adoption scene asserts that no new
search began after adoption rather than that the route is cell-for-cell identical, because adoption re-straightens,
trims and fast-forwards, so cell-for-cell equality is not a property the design has.

## Where to look

- `bot/pathfinder/SearchScope.java`, `ThreatSnapshot.java`, `SearchAware.java` — the per-search data and how it arrives.
- `bot/pathfinder/constraints/` — `ForbidRegion`, `Corridor`, `MobCluster`, `SightExposure`, `NoPlace`, and the existing
  leash and column constraints whose rejoin rule the new ones extend.
- `bot/pathfinder/modifiers/PreferBreak.java` — the only-add surcharge.
- `bot/RouteParams.java` — the pure parser, shared by `goto` and `follow`.
- `bot/PreviewSearch.java` and `bot/movement/WalkerPlanAdoption.java` — preview and adoption.
- `bot/process/RouteEvents.java` — the three events and the debounce.
- `bot/SceneOverlays.java` — the sight and mob-density overlays.
- `mcp/catalog/BotTools.java` — the advertised schema, and the migration note for the removed fields.
