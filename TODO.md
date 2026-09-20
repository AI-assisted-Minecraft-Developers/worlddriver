# TODO

Open work, and nothing else. Each entry says what is not done and where to look; a few lines is
the budget. Why a change was made belongs in `CHANGELOG.md`, why code is shaped a given way
belongs in a comment beside that code, and what order the work happens in belongs in `ROADMAP.md`.
Investigation records, evidence tables and accounts of how a conclusion was reached do not belong
in any of those, and they do not belong here.

When an item is finished, delete the entry in the same commit that finishes it.

Entries marked **Unverified** were carried across from an earlier working log and have not been
checked against the current tree. Read the code before acting on one.

---

## Engine and mod code

### The shore scan can aim a drowning body at lava

`AutoSwim.nearestShore` tests `isHazard` on the block below a candidate foot cell only, while
`WorldView.canStandAt` tests all three cells. Lava is passable and is not water, so a lava foot
cell over stone satisfies every clause the scan checks. The fix is to reject a hazardous foot or
head cell as well; the caller's null branch already holds the jump key to keep rising, so the
degradation is benign. Needs a scene that fails without the added clause.

### One documented settings range is not enforced

`SettingsDocs` declares a range for `lowHealthCareful` and nothing rejects a value outside it.
`fleeDangerBoost` has a rejection branch in `SettingsCommand`, and that file's own javadoc records
that this is the only documented range on the surface with no check. Any scene added for this must
fail while the check is absent, rather than asserting a value that cannot be out of range.

### `PlaceNearby` and its test-side twin disagree about what counts as support

The engine copy accepts any support block that is neither air nor replaceable. The test side asks
whether the face is sturdy facing up. The engine copy therefore still clicks at a lily pad. The two
must change together, and the change wants its own check on both loaders rather than riding along
with a ladder run.

### `isSolid()` as a support rule is copied three times

`BuildProcess`, `BackfillProcess` and `BboxFillProcess` each carry it. `BuildProcess`'s javadoc
records that this rule is narrower than the one the repository settled on, rejecting leaves and
dirt paths. Separately, the build and backfill paths choose a stance with no reach test;
`BboxFillProcess` annotates that difference without fixing it. This is a loosening change, so it
needs its own check.

### `BotUtil.canStandHereStatic` has no hazard clause and `WorldView.canStandAt` does

Magma blocks and lava foot cells are legal to the selector and refused by the pathfinder, so a cell
the search will never expand is handed to the walker as a goal. `GoalResolver`'s javadoc already
names the helper as hazard-blind. Tightening this changes the result of `goto block:` and of two
fill verbs. Hold until the walker census names which cells the walker is actually refusing, so the
two effects do not become one attribution problem.

### A continuation search is discarded and re-issued, and the loop does not terminate

`WalkerTickStallDetect` hand-copies half of `SegmentCommit.reset()`: it clears the active search and
the search-from-end flag and leaves the committed end and the best-effort path, which is exactly the
state the repath phase re-triggers on. Calling `reset()` instead is not safe — it also clears the
pending segment, so each field has to be decided separately. Judge a fix on a distribution (the
largest repeat count for one start-and-goal pair, and the `goto` completion rate), not on whether a
single ladder run passes.

### The futile-search governor cannot fire against a moving target

`Walker.setGoal` clears the governor, and the first search after a reset is seeded rather than
judged, so it never enters the consecutive count. A combat process re-issues the goal each time the
target moves, so half of every search population is seeding and the streak never reaches its
threshold. The criterion and the situation it guards against are mutually exclusive. Candidates, in
order of blast radius: keep the governor when the goal has only moved and the pursuit is the same;
count over a time window instead of a number of searches, without clearing on reset; throttle
re-targeting in the combat process. Grep every writer of the governor field before choosing.

### The futile-search gate has two measured blind spots

A search that reached the goal but could not be walked is never counted, and the counter clears
whenever the foot moves more than two blocks, so a five-block oscillation resets it every time.
Widen the existing gate rather than adding a second governor — the search phase's own comment says
two governors on one loop race — and reuse the existing cap rather than adding a config knob. Replay
the recorded corpus offline first: a new counting rule has to catch the two known cases without
firing on an ordinary detour around a tree.

### The lava reflex hands control back while the body is still burning

`LavaProximityEscape` decides it is clear from the environment alone — a nearby threat or being in
lava — and never asks `isOnFire()` or `getRemainingFireTicks()`. Leaving lava does not put a body
out, so the reflex releases control with the body still alight. Adding the fire test to that
condition is not by itself the fix: the reflex's action is to walk away from lava, and a burning
body with no lava left near it does not benefit from walking further.

### Every reflex is off on the body that drives the survival run

`autoRetreat`, `autoFight`, `autoDodge`, `autoHeal`, `autoShield` and `autoEquip` are all false by
default. That default is deliberate and is documented beside the field: a quiet bot stays quiet so a
scripted scenario is not overridden. The survival run is the case that wants them on. The shape is
decided — arm them for that body only, healing before retreat, because retreat preempts the walker
and healing only contends for the use key — but it is blocked on a health-loss distribution, because
arming them is a change that touches every rung at once.

### `commandForward` is a strictly weaker channel than `commandMove`

`AvatarInput.tick` lets the commanded move win over the raw commanded move, and the walker issues a
commanded move every tick, so a same-tick stop from a fallback is silently dropped and
`BotInput.stop`'s documented contract is false whenever the walker is active. Promoting the channel
changes how fallbacks and processes arbitrate, on every topology.

### Four routes reach the same `AvatarInput`

The `Body` interface, the retired `BotInput`, a copy inside `AutoSwim`, and the two surviving uses
of the vanilla key mappings. Only `Body` can drive a server-side body. Moving the other three onto
it is an architecture decision, not a tidying pass.

### A process's `attach()` does not clear the previous run's terminal fields

`goalReached`, `endReason` and `finalDist` are kept deliberately by `reset()` and should be cleared
by `attach()`; only one file under `bot/process/` clears `goalReached` at all. `mc.bot.status`
therefore reports the previous run's verdict against a run that is still going. Collect this into a
single `beginRun()`; it changes an observable surface, so it needs a check.

### Fourteen flags default off and no real run has ever turned one on

`allowParkour4` is one of them, which means the movement members behind it have never taken a step
in real navigation. Each of them needs either a scene that exercises it or deletion. Leaving them as
features awaiting activation is the outcome to avoid.

### The head-cell hazard test covers three parkour moves out of nine

The ascending, descending and diagonally ascending moves test `isHazard` two cells above the origin.
The longer leaps, their diagonal variants and the placing variant test passability only, and hazard
and passability are not exclusive — fire, berry bushes and powder snow are all passable. This
changes the edge set the pathfinder may expand, so it needs its own check and its own attribution.

### Only one of three run-up rules is the strict one

The two `Move.hasRunway` overloads require zero or two clear cells behind the jump; an inlined copy
inside the ascending move requires one. The four longest leaps all use the zero-cell version. Same
check as the hazard test above.

### The smelt wait does not verify the body is present

It reads the furnace block every tick and never reads distance, and a collect has succeeded from
sixty blocks away. Beyond interaction range it should record an error or fail.

### A step-up still jumps in place in water deeper than 0.4

Vanilla swims and rises by bumping into the bank. Two client scenes measure the behaviour. Fixing it
requires `WorldView` to carry fluid height.

### The retired water model's predicates and costs are still in the tree

The floating-water family and the water-cell, submerged, climb-out and descend surcharges are mostly
unreachable once the surface-water-nodes flag is on. Run both models side by side for a while, then
delete one; the design note is in the water-model document.

### A body that climbs out of a trench walks back into it

After reaching the top, the search routes around the trench end with a two-dimensional parkour move,
lands short and falls back in, then climbs again. The parkour landing rule takes no account of the
pillar the body has just placed or of the trench mouth. **Unverified.**

### Digging a bank block by hand while afloat costs about 370 ticks

Bobbing resets the destroy progress repeatedly, so one riser is dug in many separate stretches. Same
family as the one-high and two-high bank dig-outs. **Unverified.**

### A straightened edge longer than three blocks on dry land re-plans every tick

The stall detector measures three-dimensional distance to the far end of a straightened edge, so the
moment a flat run is pulled into one long edge the body counts as knocked off it and re-searches
every tick until the last few cells. Water already limits the span; dry land still takes the full
storm. Measuring to the edge rather than to its end removes the searches, but four scenes covering
leashes and shore takeover depend on the current cadence and turn red. Decouple those first.

### The deep-water drift brake disables sprint on a two-block shore margin

It now decides half the price difference between walking the shore and swimming across.
**Unverified.**

### Two tools disagree about the day-phase vocabulary and its boundaries

`mc.observe.player`'s time phase and `mc.client.scene.dayPhase` contradict each other in two
windows. Deliberately deferred; the reasoning is in `WorldModel.dayPhase`'s javadoc. Reopen when
the dusk-securing chain's firing window changes.

### Three line samplers share one piece of arithmetic

`losWalkable`, `straightLineBias` and `isOpenWaterLine` each compute the same step count and
interpolation. Extract only the per-step cell computation, not an iterator returning a list: almost
all sliced pathfinding sits inside a single tick's budget, and allocation is expensive by count.

### `activeProcessDetail` is null while the dusk-securing chain owns the bunker

The bot API reads only the foreground user task's status detail, so a driver has to infer the
bunker's state from the active chain plus sky exposure. **Unverified.**

### Single-shot actions on the client avatar are issued from the server thread

The correct shape is to issue from the client tick chain and let the scene observe the outcome
through the world. Only `wd.actuatorSplitThroughTheClientAvatar` can judge this seam, and the
survival run's topology cannot reach it.

### Client state is written from the server thread beyond the one path already guarded

The concurrent-modification crash came out of `ClientLevel.playSound`, and block placement was only
one caller of it. `useItemInHand` (the bucket path) and `continueDestroy` are unguarded; holding an
item, setting the selected slot, container clicks, recipe placement and unchecked attacks also write
client state from scene threads.

The guarded path's shape — hand the work off and return — cannot be copied. `JourneyFill` reads the
bucket count immediately after the call and judges on the difference, so deferring the use makes it
always read as a failed fill; any fix has to change those call sites too, either by settling once
before reading the stock or by moving them onto the pending-stop mechanism. The hold-item family is
the same defect with a smaller blast radius (a bare int field rather than a map, so the worst case
is a stale read), but its return value is load-bearing — callers assert on it — so fire-and-forget
would turn it into a lie. Either the callers' contract changes or holding and using share one
handoff.

### The farm process's break timeout is a private constant

Sixty ticks, hard-coded, not wired to the configured break timeout.

### Descend and escape end differently on a shared state slot

Escape resets the slot and logs a bail on failure; descend only clears the active flag. After a
descend, the escape slot still reports the old goal, target and start time, and a failed descend
logs nothing at all. The shared slot itself is documented design and is not the defect. Aligning the
two changes what `mc.bot.state` reports, so it needs a check.

### `isFalling(Level, BlockPos)` exists three times, verbatim

In the bunker, descend and escape processes. Do not merge it into the world view's falling-block
check: that one reads through a view which may belong to another dimension. A shared static helper
is safe but buys three lines each.

### Two `fail(...)` helpers take a state parameter they never use

In the craft and smelt processes.

### The descend process's action counter is not cleared across phases

### There are four different reach distances

4.0, 4.3, 4.3 and 4.4. **Unverified.**

### The lava reflex prints a cell with an integer cast

Truncation toward zero, so at negative coordinates it prints a cell the body is not standing in.
Three places repository-wide. **Unverified.**

### Configuration is a global singleton holding per-body, per-tick state

The fields are `public static volatile`, so scenes cannot safely run in parallel. Same file and the
same split as the size-budget work below.

### The freeze window family is not even measured yet

Fluid flags are frozen while the driver is not registered. Roughly thirty scenes and around a
hundred read points share the symptom, and no measurement has landed. **Unverified.**

### The script prelude and the typed parameter reader coerce differently

The prelude's truncation accepts a fractional number, a numeric string and a wrapped value that the
typed getters and the schema validator reject, so the script channel is more permissive than the
other two transports. This is a behaviour change and needs a check.

### A game verb lives in the script transport

The prelude's tunnel function is a whole verb — it clears a corridor of a given width and height in
a direction — and only the in-process engine can reach it: there is no router entry and no catalog
schema, so Model Context Protocol and remote-procedure-call clients cannot call it at all. It cannot
simply be deleted, because a validation script exercises it.

Promote it to a real route, in this order: add the catalog schema, add the router entry with the
implementation moved under `bot/`, then reduce the prelude function to a thin forward so the
validation script needs no change, then run both loaders' checks and the script validation suite.
Delegate direction handling to `GoalResolver.applyDirection`, which is the only one of the three
direction vocabularies that is a superset and the only one that normalises. Keep `distance`
mandatory — the prelude's own comment explains why defaulting it silently turns a missing distance
into a one-cell dig. Do not fold the unification of the two backward direction words into this
change; it is a separate piece of work and mixing them makes a failure unattributable.

### The script sandbox assertions guard a filter that is switched off

`ScriptClassFilter` is disabled unless `-Dworlddriver.sandbox=on` is set, and nothing in the build
sets it, so the class filter refuses no name during any check. The seven assertions in the sandbox
validation script are green throughout, and they are named in `AGENTS.md` as the guard against the
sandbox being widened — which they cannot be, since they would not go red when it is.

Do not add assertions yet; two exact coverage totals would move. The reading needed first is what
`java` and `java.io.File` resolve to inside the script context. If they are undefined, the seven
assertions are vacuous — the denials are catching a type error rather than the filter — and that can
be established without enabling the filter at all. Only if they do resolve is a run with the filter
enabled worth taking. Two of the seven cannot distinguish anything in principle: the host has no
such executable and a connection to a closed port always throws.

---

## Body equivalence

These belong to the body-equivalence role and are bounded by the parity document.

### The selected-slot section has three residues

The client twin of stopping an item use, a one-round-trip window, and a one-tick content lag between
the bag and the hand. The parity document's own note says not to fold the next piece of work into
the same change. **Unverified.**

### A furnace menu sixty blocks away is never closed

A real player's tick closes containers; the joined body drifted that far with the menu still open.
The symptom is observed and the cause is not. **Unverified.**

### Aiming is still split between the two sides

The twin has not landed. **Unverified.**

### `selected` is written by four methods across five lines

The earlier count of three places was wrong. **Unverified.**

### Digging fidelity on the server-side avatar

Bare-handed obsidian breaks, silk touch does not apply, and tools take no durability.
**Unverified.**

### The joined body's tick is an empty implementation

The second half of the join work is to drive it by writing inputs rather than by copying a player's
tick. **Unverified.**

### Five smaller parity gaps

Pitch has two owners, so the driver does not look down while mining; there is no hand swing and it
needs a probe of its own; best-weapon selection is called only on the Nether rung; a workbench is
neither reclaimed nor are dropped items picked up; and the first moments of a run read as night
until a spawn-time row is added. **Unverified.**

---

## Test harness, ledger and instrumentation

### A scene failure does not reach the ledger

`ctx.fail` does not flow into the ledger's failure field, so the ledger never records why a rung
failed. **Unverified.**

### The fall self-report names the process, not the action

The leaving report says which process was running, and not which movement edge the walker was
executing or which jump tag was in play. One family of falls has been guessed at across several
rounds for want of an action to go with the location.

### The heartbeat cannot attribute horizontal drift

It carries no delta movement, no input flags and no fluid flow, so a long lateral drift cannot be
assigned to residual input or to a current. **Unverified.**

### The stacked-javadoc checker cannot see the worse form of the defect

An abandoned documentation block that lands above an undocumented member is not discarded — javac
silently adopts it, and that member now ships someone else's contract. The missing reading is a
mismatch between a block and the declaration it is attached to: a `@param` naming a parameter that
does not exist, a `@return` on a void member. It does not need natural language. The script records
this in its own header.

### Evidence keys in the climb and exit families carry no tag and overwrite each other

**Unverified.**

### Mob cap, chunk loading and terrain change have no reading at all

**Unverified.**

### Two of the late rungs have no stock evidence rows

Three recipe tables are waiting on them. **Unverified.**

### The coverage line's executed count excludes scenes that ran and failed

A failure counts as neither executed nor skipped. The coverage column also still needs the skip
count beside it.

### An advancement says not earned while the bag holds the item

The blaze-rod advancement. Measure whether the inventory-changed trigger fires for a joined body
before believing either side of it. **Unverified.**

---

## The survival ladder

The end-to-end survival run. The three standing optional failures in the scene suite
(`wd.vineOverWaterClimb`, `wd.serverEscapeSealedShelter`, `wd.journeyGetsAshoreBeforePouring`) are
tracked here rather than as suite defects.

### A run that did not finish reported success

The client dropped out partway through the fourteenth rung, that rung wrote no results at all, and
the build still exited zero. Two separate things: a run that did not finish must fail the build,
and the disconnection needs a cause of its own. Nothing about the fourteenth rung can be verified
until the first half is fixed, because the corridor has never been run end to end.

### The fourteenth rung's fifth leg is the current wall

The body retreats westward, falls repeatedly and dies of accumulated fall damage with food low and
no regeneration. The destination is reachable — one search solved it — but the cost is the problem:
three of four full-budget searches hit the node ceiling, and the one that solved used most of the
budget. The fix is to shorten the hops by inserting intermediate waypoints, not to move the
waypoint that cannot be reached; one cell on that stretch is already measured as standable and has
been arrived at. Choosing the intermediate points needs the corridor terrain map below.

### The corridor terrain map has never been written

`JourneyCorridorProbe.record` is wired to the paths where a leg is abandoned, and not to the path
where the body dies — and both corridor runs ended in death. Wire the death path too.

### The full-budget search tier has no clock

It runs effectively without a time bound, at several seconds per call. Two questions: whether that
tier should have one at all, and why a thirteen-block leg over open lava takes tens of thousands of
nodes, which says the heuristic is not converging there.

### There is no eating anywhere on the fourteenth rung

`JourneyFeed.eatIfLow` has exactly one call site in the whole project, on the gravel rung, and the
raw beef is all eaten there. Two halves that are useless apart: cook the beef, placed after the iron
smelt where a furnace is already down and the sequence has been exercised, and add one eat call in
the corridor, between legs rather than mid-leg. Before writing the cooking half, check what the
smelt process burns and where the fuel is drawn from when its third argument is null — iron and meat
draw on the same pool.

### The corridor audit's acceptance check has nowhere to run

The audit runs at the corridor start, and a failure there cuts off the evidence the check needs. It
wants an optional scene of its own after the thirteenth rung, registered together with its rows in
both loaders' expected-scene manifests in one commit.

### The corridor fire needs a replan trigger, not a surcharge

Decided; not yet built. Pricing the cells next to fire is withdrawn and should not be picked up
again, and so is the per-tick comparison of the body's cell against the path node, which existed
only to take the surcharge approach apart.

### Who lights the corridor fire is unknown

The reading says only that the block is in the fire tag; ghasts and fire spreading over netherrack
are both unexcluded. The replan trigger does not depend on the answer.

### The quick-start pathfinder stub could be lengthened in the corridor

It is a writable setting, currently a few hundred nodes, and raising it gives the body a longer stub
to walk during the seconds a full search takes. Deliberately kept out of the corridor change set so
that a run's outcome stays attributable.

### A raise reports a height where the caller needs a column

The verdict row already says the tower landed off the named column and that the cell beneath is not
a floor, and the caller reads only the height. The upstream cause has been fixed, so this is now the
second line of defence rather than the first, and the situation has stopped appearing because the
lift tower is no longer being reached. Do not delete it: the terrain that forces the tower recurs.
Reopen the moment a run uses the lift tower again.

### When the tower stops, it says so only in the evidence

It correctly detects that two successive rewrites undo each other and stops, which is the right
behaviour, but that fact never reaches the return value — the caller sees only a height. Same
occasion and same reopen condition as the entry above.

### A "gained N of N" reading can be true with the body airborne and off the column

It measures a height difference. It must never stand alone as a success criterion: success needs
the ending cell to be the named column and the cell below it to be solid. The climb's closing step
should combine the three into one verdict instead of leaving a reader to cross-check them.

### Aiming happens while the body is still falling

The settle judgement uses cell numbers and the aim uses exact coordinates, so a body most of a block
above its cell fires a ray that clips the frame. The reading has landed — footing height and
on-ground are printed beside each aim — and the fix has not. The draft criterion is to keep waiting
while the body is airborne and to stop waiting once the foot drops below the starting row, and it is
known not to hold for one case, which needs a second branch that re-picks the stance rather than
aiming from where it is. Waiting longer is not the fix: a body in the air only falls further. After
landing the aim must be recomputed, because what is stored is an angle and not a target.

### The arrival leg answers a zero-block question with a five-block tolerance

Both remedies it names fire, and both report success by their own criteria, while the body is still
off the named column — because those criteria count how many steps were laid, not where the body
ended.

### The fill stance scan runs twice and the first pass discards its reason map

The flag that makes the two passes differ applies only to lava, so for water they are equivalent and
the first pass always answers — which means the reason map the caller reads is permanently empty,
exactly in the failure shape where the answer is "the only stance found is the cell the body is
already in". Give the first pass a real map, merge whichever pass answered, and name in the evidence
which one it was.

### Two bucket-fill implementations coexist

The scene-side one and `JourneyFill`, and the eleventh rung still runs the scene-side one — which
lacks the reach margin, the re-aim, the source change and the fill station, and discards the
hold-for-use return value, so a missing bucket is charged to "did not fill". The first step is only
the matching guard that names "could not hold the bucket". Merging the two waits for a round in
which evidence keys are allowed to change.

### Three changes to the pour aim, in this order

First, the five silent `continue`s in the aim search write nothing, so "re-asked, refused, fired
anyway" leaves no trace. Second, the failure verdict reports geometry when the truth is that the
body is not standing on the stance it chose for itself, which sends the next reader to the wrong
place. Third — the only behavioural change — record the stances the body could not reach and exclude
them on retry, clearing the set per pour; today the retry is byte-for-byte identical to the first
attempt because the stance chooser cannot know the body failed to get there. Do not widen the
blocker scan to start at the target's own column: that column is the mould's frame.

### The daylight height expression is hand-written nine times

A helper already exists for it. Check each call site's declared level type before substituting; one
of them wants a block position rather than an integer. Compilation is the only check needed, since
no evidence key changes.

### One ascent entry point records no exit

It climbs a lava corridor and writes none of the six exit rows. The other twelve entry points stay
as they are: fill one in only when a verdict blames a climb's outcome and that segment has none of
the exit rows, and then fill in only that one.

### A pit-rim surcharge is recomputed every run for no change

The cell count does not move between runs, so the full scan each time is pure cost. Snapshot the
outbound computation and reuse it; the surcharge itself should not change, since it is what bought
a run with no lava deaths.

### A shaft's exit climb is washed off the pillar repeatedly

Both descent columns were ruined by fluid; the code diagnosed and decided correctly, and what ate
the run was the exit climb being washed off each time it placed a block. What is needed first is
provenance for each water cell encountered — natural, left by an earlier rung, or self-poured — in
the same form the pour side already records. Neither leg should be changed before that has a
distribution, and "tower started in flowing water" and "not enough column retries" must not be
merged into one fix: they are two legs.

### Water reaches the stairwell floor and the family is not yet named

The upstream scan now reports no source blocks within range, so this is runoff rather than a live
source being fed. The water-source-lifetime change — placing the water only once the lava is in hand,
which shortens its life from a long round trip to the few dozen ticks of the pour — must not be
started until the drain reading says which family this is.

### The drain rows do not say how much was cleared or how long it took

They say only that the alcove is drained, or that fluid remained after the wait. Add the cell count
and the tick count.

### A shaft column change moves to a column in the same water

Requiring a dry column is not the answer: a measured sweep rejected every candidate around this
seed's lake, because the water table is simply there. The real problem is that descending into water
needs a technique — sealing the source, taking it, or a different digging order. Read the
diggability helper's javadoc before reopening this.

### Two message texts are wrong

A stuck-step annotation attributes the refusal to one of the three booleans before it and omits the
fourth conjunct it printed on the same line, sending the reader to look for a source that does not
exist. A scene's top-level fallback reason contradicts what runs actually report. Change the text in
both; do not add fields.

### The cell-clearing pass is single-pass and the failures are arrivals that did not happen

The pass advances monotonically and never returns to a cell it could not open. Separately, the
arrival radius it uses is provably tighter than the interaction limit, so "arrived but out of range"
cannot happen — which means a failure there is a leg that did not arrive. The fix direction is to
sweep until no progress is made, not to widen the arrival radius and not to relax the no-break
constraint on that leg.

### An enderman fight sometimes never ends

About one NeoForge run in four fails here. Every fight either finishes quickly or burns exactly the
tick ceiling, with no value in between across two dozen fights, so this is a hang rather than
slowness. Add one row per ceiling-burning fight carrying four fields: whether the target is still
alive, how far the body is from it, the largest jump in the target's position during the fight, and
how many combat searches ran. Those three families — target lost, re-search storm, neither —
partition the answer. Do not lower the pass criterion: it is already equivalent to winning three
fights out of six, which is the floor.

### There is no reading for who wrote a cell

A guard refused to mine a cell because it would release the water behind it, and the body later
drowned standing in that water. Nothing can say who opened it — the walker's fallback pathing
carries its own permission to break, and is the first candidate. What is needed is a
who-wrote-this-cell attribution row, not another round of inference.

### The pit-rim band is scanned above the lava only

It does not protect a body already down in the pit. The leg-attribution reading has landed; collect
samples before deciding whether to extend the band downward.

### The stranded branch of the walk home does not settle onto ground

The arrived branch does; the stranded branch records only where it stranded and moves on. The lost
body is the one that most needs the reading.

### The tower-reclaim row records only the count after dismantling

Record the count before as well, so how much came back is measured rather than inferred from net
loss.

### Arrival is hard-coded at five blocks while callers pass a tolerance

The shared walk-to-column helper feeds the caller's tolerance to the goal and then judges arrival
against a hard-coded five-block radius. Eight of fourteen call sites pass zero, so tightening all of
them at once turns eight legs red simultaneously and nothing is attributable. Do this in order: add
an evidence row that fires when arrival fell between the caller's tolerance and the hard-coded
radius, and run once to see who is relying on the slack; then give the callers that need precision
their own path; then handle the rest one at a time. The first step changes no criterion and cannot
change any scene's colour.

### A floating body cannot walk onto a bank at its own level

This is what keeps `wd.journeyGetsAshoreBeforePouring` failing, and that scene is the only standing
witness to the engine-side defect. A scripted step that places a block under the body is
geometrically impossible here — the bank is one row above the body and the cell below it is one row
lower still, and placing into the body's own cell is refused by vanilla.

The engine-side half: the walker's final node is accepted by the horizontal proximity clause with
zero footing under the body, and the existing airborne-climb guard cannot be reused, because it is
scoped to mid-path nodes and it excludes bodies in water for a measured reason. A second judgement
sits above it on the test side, so two layers each call this an arrival; a fix has to account for
both. Reopen the scripted step only if the walker starts delivering the body to the bank's column
and the scene is still failing.

### A retry in the ramp changes nothing, and the instrument cannot say why

Three rows are written mutually exclusively and none carries timing, so "it stepped aside, asked
again and was still refused" and "the stop reason was rewritten, so it never asked again" both fit
the archive equally well. Give those three rows a shared pass number or tick stamp, and print the
stop reason as it was before the rewrite. Do not add retries on the strength of the current reading:
if the branch was never asked a second time, more attempts change nothing.

### A ramp early-return compares rows and not columns

It appears in two places with the same shape, so changing one immediately runs into the other.
Reopen when a profile names the wall cell and the raise still reports a column other than the one it
pinned.

### Nothing records who lifted the body above the alcove

A placement row carrying coordinates would answer both that and who filled the wall cell that blocks
the descent leg. Without it the failure can be described but not attributed.

### The seat for a fill is judged from the cell centre, not the eye that fires

A change that asked the real eye landed and was then reverted, and the revert stands. The defect
itself is measured: the real eye is blocked by a specific block face at a specific distance while
the cell-centre eye has a clear line. The minimal form changes the ray's origin rather than adding
rays, and four call sites of the same shape can share one eye helper. One nearby sampler takes
offset parameters deliberately and is not part of this family.

### The rehearsal task's switches are 115 lines of build-script flags

They want to be a shared closure.

### A scanner checks the owner class and should also check descriptor parameters

A scheduler class can pass a client type without calling one, and the failure only appears when a
dedicated server constructs it.

### The ladder's progress keys need units

And an older path-length key should survive one more round before it is removed.

### Markers in the lab world vanished between runs

Twenty-five placed, all gone a few scenes later, cause unlocated. The run now logs how many markers
it lifted and restored; compare those two rows the next time it happens.

---

## Source-size budget

`scripts/check_source_budget.py` fails the build for any Java file over 3000 lines. Several files
sit within a few lines of it — the journey rig, the configuration class, the walker, the Nether
rungs, the journey scenes and the End rungs, in that order. Run the script for the current numbers
rather than trusting a number written here. The remedy is splitting; stripping comments to buy
headroom trades readability for quota and is not one.

### Splitting the configuration class has a mandatory first step

Make its persistable-field enumeration walk the superclass chain before moving any field. Two
enumerators in this codebase each document themselves as the one enumeration and each is right about
itself: the settings registry uses `getFields()` and follows superclasses, the configuration class's
own persistence uses `getDeclaredFields()` and does not. That difference decides how a split fails.
A sibling-class split throws immediately, which is safe. A superclass split is silent — the moved
fields stay in the settings snapshot and read normally, while falling out of persistence and out of
the snapshot-all path, so a test baseline leaks into a live bot for exactly those fields and the
symptom looks like a few rungs quietly regressing. Making the enumeration follow the superclass
chain is provably a no-op today, because the superclass is `Object`; that makes the two steps
separately verifiable.

### Two things in this area that must not be tidied

The private stance checks in the build and backfill processes are byte-identical to each other but
stricter than the shared helper — they lack two water clauses, so a waterlogged cell is standable to
the shared version and refused by them. Merging would loosen two placement paths, and wants a
measurement first.

"No Java callers" does not mean dead here. The script class filter is a denylist that is off by
default and does not deny this project's own packages, so any public member is in principle callable
by name from a script at runtime; and the settings registry, the settings command and the
configuration class all reflect on configuration fields by name. Every candidate from a dead-code
sweep has to be re-checked against both of those before it is removed.

---

## Decided against, with the condition that would reopen it

These are settled. Each line is the decision and the observation that would overturn it. Do not
reopen one without that observation.

- **Unifying the arrival radius across five call sites.** No measured divergence has ever been
  charged to it, and the baseline those radii are measured from is itself still moving. Reopen once
  the ray-casting family settles.
- **Promoting the contact-damage and lava-proximity escapes to a different movement channel.** The
  first five occurrences of the reflex all succeeded; the failure was forty ticks inside a source
  block with less than a block of movement available, which no driver changes. Reopen if a death
  reading ever charges an escape failure to steering or to driver latency.
- **A global arbitrator for the attack key's five users.** A diagnostic table replaced it and the
  affected rung has passed consistently since. Reopen if that rung fails again with an unclear
  attribution.
- **Widening the retry budget on the in-place lift.** All three approaches are byte-identical
  because the gate compares heights and what fails is the column. The three split readings have
  never produced a row. Judge on the first one that does.
- **Re-picking a blocked pour target more times.** The predicted fourth state has never appeared in
  any archived result. When it first does, the direction is already fixed: change the question (via
  a midpoint, or walk to the column) rather than raising the count.
- **Checking the target cell is empty before placing.** Refusals consume nothing and write nothing;
  the measured ratio is well under one refusal per success, clustered on individual cells. Reopen
  when a run's failure or exhausted budget is charged to repeated refusals.
- **Reporting a mid-flight elytra exit through the error field.** That exit is the only consumer's
  sole path and the late rungs do not use elytra at all. Reopen if elytra enters the main line.
- **The shaft support helper reading the context's level rather than the body's.** Latent — every
  caller is currently in the overworld. Its twin in the End rungs has a byte-identical body and
  reads a different level, so merging the two would break the late rungs. This family raises no
  error of its own, so the reopen condition has to be executed by a person: the first time any of
  the shaft's methods appears in a Nether or End rung's call graph, which is the first step of
  starting work on those rungs.
- **Splitting the journey rig behind a facade.** The seam is real and clean, but it costs nearly two
  hundred call sites across a dozen files for no behavioural gain. It is now up against the size
  budget above, which is where it will be decided.
- **Taking material for towers and unsticking from downstream demand.** The measured net cost is
  zero. Reopen when a verdict charges a failure to material supply.
- **Reconciling the two break-cost multipliers.** The motivation is gone: the local exemption that
  replaced it is verified with the shipped configuration. The multipliers' remaining global effect
  belongs to the two-world-views pricing item, not here.
- **Recording a skipped scene as something other than a pass.** The judge already separates the two
  — it prints skips distinctly, excludes them from the executed count and folds them into the
  coverage line — and the outcome field cannot change, because failure is judged by inequality with
  pass, so writing anything else would turn every skip into a required failure. What needs stating
  is the protocol for reading the results file. Reopen if a coverage line ever disagrees with the
  number of skipped records in the results file.
- **Adding a fifth remedial leg to the portal rung**, and **changing the raise-in-column or ramp
  logic on the strength of an off-column reading**: those readings appear byte-identical in passing
  runs, so they are necessary-but-insufficient at best.
- **The nine placement refusals during staging.** They occur outside the scored region, consume
  nothing, and the tower they belong to was built anyway. Reopen when at least five refusals on one
  cell coincide with a failing outcome in the same segment; the fix direction is then to recompute
  the clicked cell from the body's current position rather than reusing the one chosen when the leg
  began.
- **Adding no-break constraints to the five remaining legs inside the mould.** Two legs were given
  one each on three-way evidence; these five have none, and adding the constraint has a measured
  cost — one of the five is the recovery leg that climbs out of the pit, which may have no route at
  all if it cannot dig. Add one only when a run shows a broken stair step, a dig on the staircase's
  supporting diagonal, and a segment whose search owner and goal match that specific leg.
- **Extending the drain scan to the pour line.** The two scans read the same set by construction —
  the "inside the alcove" label is membership in the corridor the drain scans. Reopen if a pour-line
  water row is ever labelled outside the alcove while the drain reports the alcove clear.

---

## Leads not yet checked against the current tree

A line-by-line sweep of the retired working log produced these. **None of them has been checked
against the current tree**, and several have already turned out to be closed — a file recorded here
as over the size budget was comfortably under it when measured. Before acting on one, grep for the
line the code itself would write, read the production code, and measure the file.

- Escape and descend disagree on their reset conventions.
- There is no lava self-rescue at all, and a body that falls in burns the remainder of the segment's
  budget walking.
- A parkour take-off gate: refuse the jump when less than one block of horizontal room remains. The
  fatal and surviving samples separate cleanly with no overlap, so the predicate is already known to
  the threshold; the change has not been made. Two void-parkour scenes must go green together.
- Two of the climb guards cannot reach a cell that is fully submerged.
- Roughly sixty-five places drive global key bindings inside a tick, and the chain that stops
  destroying a block every tick while the window is unfocused has never been measured.
- Place and break permissions are one concept with two enforcement paths.
- An executor leaves its own plan and cannot get back to it — a family, not one case.
- The seventeenth-rung march treats displacement as progress, which is the third instance of that
  shape.
- The alcove does not drain fully; the cause is half known and the behaviour is now a cost rather
  than a blocker.
- A third way of dying on the eleventh rung: over-digging by a dozen blocks.
- Two more buckets are needed on the ninth rung; the code half has landed and the iron half has not,
  because the amount mined is below what the bill requires.
- The fifteenth rung cannot reach the warped forest, with the body sitting in lava.
- The lava lake is a cave lake with a one-block-thick ceiling; two candidate fixes, neither tried.
- On the fourteenth and fifteenth rungs, the walker's exit is the next step, and one expansion
  is walled in with nobody digging it out.
- The survival run completes in peaceful mode: three options, and the choice is a user's to make.

### Two pointers rather than copies

Documentation debt is tracked in the documentation map's own outstanding list, and belongs to the
documentation role rather than here.

The workspace-root working log and gap list are not in any repository and have not been touched
since the split. Every item in them predates this project's current shape and none has been checked
against the current tree. Treat them the same way as the leads above.
