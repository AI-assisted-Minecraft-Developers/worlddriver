# Water: only the surface layer is a node

## The problem

The pathfinder asks the world whether the body can stand on a cell. For a long time the
answer for water was yes at any depth: `isWater(foot)` stood in for "there is something
solid under the feet", so every cell of a lake was a node and crossing one was an ordinary
`Walk` edge priced at ten.

A real body cannot do any of that. Buoyancy holds it at the surface; there is no ground
underwater to jump from and no depth it can walk through horizontally. So A* kept planning
routes the executor could not follow, and the repository kept patching the gap: four
buoyancy predicates (`isFloatingWater`, `isSubmergedAscent`, `isSubmergedFoot`,
`isDeepWaterSurfaceLanding`), eight separate taxes, and dozens of guards spread across the
move classes saying "a floating body cannot jump" or "do not descend into deep water".
Every one of them was a patch on the same false premise.

There was a second fiction alongside it. `SwimUp` was allowed to surface from a water cell
into the air cell above it whenever anything solid sat nearby. That edge was the
pathfinder's way of saying "get out of the water here", and the executor's water climb-out
takeover in `WalkerTickClimb.pillarTakeoverTick` is what actually performs it — placing a
foothold, pressing against a bank, or digging the bank away. The edge asked neither whether
the body held a block nor whether it could end up standing, so A* could site an exit on an
open column of water where the body could only bob.

## What was decided

`BotConfig.pathfinderSurfaceWaterNodes` (on by default) replaces the premise rather than
patching it.

**Lateral nodes exist only at the surface.** `canStandAt` returns false for a cell whose
foot and head are both water. Submerged cells appear only on the `swimUp` / `swimDown` /
`swimDownSurface` chains; no horizontal move may land on one. A body that starts underwater
— because it fell in, or is drowning — searches from where it is, and its first step can
only be upward.

**Leaving the water is one edge.** `SurfaceClimbOut` (edge name `climbOutPlace`) goes from
a surface water cell to the air cell above it, and it is legal only in the three shapes the
takeover can really perform, and only with a block in hand: water one deep, so a ground jump
and a fill will do it; an open neighbour at foot level with a floor under it, giving a side
rung; or a solid block beside the risen feet, so vanilla's swim boost against a bank lifts
the body clear. It is priced at twenty-five, the same as the `swimUp` edge it replaces, and
the climb-out tax still applies on top — the two models differ in what is legal, not in what
is preferred.

**`SwimUp` rises only within water**, returning false when the destination is air, and
**`PillarUp` does not start from water**. A one-deep pillar-up was being taken over by the
dry-land tower executor, which then waited for a landing a wet body can never report.

**Dive searches keep the old model.** When a search is built with `Capability.DIVE`, the
view is told (`WorldView.diveSearch`) and `surfaceWaterNodes()` returns false for it. A goal
such as an underwater base requires the body to cross fully submerged, so every rule reverts.
For the same reason `Walker.snapGoalToStandable` leaves a diving goal alone: under the new
model that cell is not standable, and snapping would carry the diver somewhere else.

The executor was not changed. It decides on geometry almost everywhere — a climb-out takeover
begins when the current waypoint is above the feet and within two cells horizontally — and
only `swimDown` and the `swimAshore` family branch on the edge name. The new edge has the
same shape as the fiction it replaces, so the takeover still fires.

## What this rules out

A route that crosses a lake underwater is no longer expressible outside a dive search, and a
water exit can no longer be planned where the body has no way to make one. Both of those were
capabilities of the old model, and both were capabilities only on paper.

The four buoyancy predicates and the eight taxes are still in the tree, because turning the
flag off restores the old model, but with the flag on most of them are unreachable:
`submergedTax` can now only be hit from the `swimDown` chain, and `isSubmergedAscent` is
rejected by `canStandAt` before it is ever consulted. They are kept so the two models can be
measured against each other, and are expected to be deleted once that is settled.

Turning the model on exposed five scenes that had been written against the fiction: an
underwater base that required the dive relaxation, a scene deliberately reproducing the old
bug, a scene where the server body had been swimming up a two-block bank that a real client
never manages, a crafting-spot chooser that had been picking the bottom of a pool, and one
aiming defect described below. They were restaged rather than exempted.

## Open water: vanilla's prone sprint-swim

A body that can only tread water crosses a lake at about two blocks per second. Planning was
never the bottleneck — a fifty-two-block crossing takes the search roughly 458 nodes and 39
milliseconds — and the cost was split between a pathfinder that priced deep water as a death
zone, so routes hugged the shore, and an executor that could not sprint.

That second half is a vanilla rule. `LocalPlayer.aiStep` accepts a sprint in water only while
the eyes are submerged, and staying afloat means holding the jump key, which keeps the head
above the surface. So the sprint requested every tick was cancelled before `travel` ran.

The vanilla rules, checked against a decompiled 1.21.1, are these. A sprint in water is
accepted only when `isUnderWater()`, and is cancelled when in water but not under it, unless
the body is already in the swimming pose. `Entity.updateSwimming` enters that pose from
"sprinting and eyes submerged and the foot cell is water", and holds it as long as the body is
sprinting and in water. Within the pose the client cancels the sprint in only two cases: no
forward impulse (with a hold on sneak exempted), or leaving the water. The pose has an eye
height of 0.4; `Player.travel` steers vertical velocity towards the look direction while the
cell at eye level is still water; swimming in liquid adds 0.04 upward per tick; and a
sprinting swimmer has no gravity and a drag of 0.9 rather than 0.8.

`WalkerTickDrive.surfaceCruise` (`BotConfig.walkerSurfaceSprintSwim`, on by default) is the
state machine that gets into that pose and stays there.

It **enters** when the current node is a surface cell at least one cell away horizontally,
with deep water below, nothing solid overhead, the eyes clear of the water, and no bank within
three cells of the next eight nodes. While cruising, the sprint comes from the cruise itself
and only danger and low health outrank it; step jumps and surface jumps are off; the drowning
backstop in `AutoSwim` and the climb-out takeover both stand aside.

It **dips** by holding sneak — vanilla's `goDownInWater`, minus 0.04 per tick — until the pose
appears, giving up after `CRUISE_DIP_MAX_TICKS` (40) and returning to treading for
`CRUISE_COOLDOWN_TICKS` (100).

It then **confirms**, sinking a further `CRUISE_SINK_TICKS` (3) and hovering until tick
`CRUISE_CONFIRM_TICKS` (12). This window exists entirely for the server. The client flips its
own sprint bit and only sends the start-sprinting packet on the following tick; when the server
applies it, it has not yet computed its own swimming bit, and it then broadcasts the whole
shared flags byte back — sneaking, sprinting and swimming all live in that one byte, and the
client's swimming bit is overwritten with zero. The pose collapses to crouching or standing,
the eye height jumps back to 1.27 or 1.62, and if the body has drifted back to the waterline
by then its eyes surface and vanilla cancels the sprint under the "in water, not under it"
rule. A human player is not caught by this because they are still deep when they enter; the
cruise deliberately holds the body deep until that echo has passed.

It then **rides the waterline** with pulsed jumps rather than a held one. Holding jump makes
`jumpInLiquid` win against the 0.9 drag and lifts the body clear out of the water, which drops
the cruise immediately. A single pulse glides for roughly nine times the vertical speed at the
time. More than 0.3 of a block below the surface the cruise pulses whenever the rise is slow;
in the last 0.3 it pulses only when vertical motion has stopped, which leaves the eyes just
clear of an eight-ninths source-block surface given the 0.4 swimming eye height, so air refills
and stays full.

Finally it **breathes**: below `CRUISE_AIR_LOW` (130) the cruise drops out to tread and
recover, and will not re-enter until air is back above `CRUISE_AIR_OK` (280). With the eyes
riding the waterline this rarely fires.

### What the cruise forced elsewhere

A cruising body sits one or two cells below its own path nodes, and four pieces of the walker
had to learn that.

Arrival (`cruiseUnder` in `WalkerTickProgress`) is judged horizontally. The height thresholds
in `within` and `passed` read every node as a step the body had failed to climb, so the path
pointer only advanced by arc-length projection after the body had already passed the next
node — permanently one node behind. The drive then steered at a node behind the body, the
forward impulse went to −1, and vanilla cancelled the sprint under the no-forward-impulse rule,
once every two cells.

Off-path detection (`offPath` in the stall detector) is judged horizontally for the same
reason: a three-dimensional cell distance called the sunk body off-path every few cells, and
the replan that followed started from the sunken feet with a run of `swimUp` edges that broke
the cruise.

String-pulling is capped: `PathSmoothing.WATER_PULL_SPAN` is 2. A forty-nine-cell straightened
edge left the pointer with nothing to advance to, and the water pre-claim then emptied the path
outright. A span of 3 brought back the off-path problem, because a sunk foot reads one cell
further away in three dimensions.

The water beeline (`tryWaterBeeline`) measures from the surface cell of the body's own column
and runs before `tryQuickStart`.

On the planner side, `pathfinderDeepWaterPriced` (on by default) prices deep water by the water
tax instead of treating it as a lethal cell in the hazard field — contact damage aside — and
`waterDangerPenalty` is 3, down from 12. **That number is the design constraint**: a cruising
body covers about 0.19 blocks per tick against 0.22 for walking, whose per-cell cost is 10, and
the remainder pays for the dip at the start of each cruise. The end-to-end effect on a
fifty-two-block lake on a real client (`wd.clientOpenWaterCross`) was 550 ticks treading against
348 ticks cruising; the intermediate states were all worse than treading, which is why every
piece above is required rather than optional.

## An aiming defect the model exposed

`wd.clientGotoStartsMidAirOverWater` went from 77 ticks to 478 on the first run under the new
model. The body reached the cell beside its goal at tick 75; the remaining 390 ticks were the
body orbiting on dry land, yaw winding from 52 degrees to 2453 with the heading error pinned
around 90. This was a pre-existing lag in the aim smoothing that happens when `stepUp` walks
the body straight out of the water without going through the climb-out takeover, so
`aimSmooth.reset()` is never called; the new model only made it visible.

The fix is `walkerOrbitBreaksAimLag` in `WalkerTickAim`: on dry land, with the body moving and
the heading error between 45 and 170 degrees, once twelve such ticks have accumulated **and the
body's own yaw has turned 180 degrees in one direction**, the smoothing factor switches from
0.08 to the cruising 0.5.

Both halves of that condition are load-bearing. The 170-degree ceiling leaves room for the
plus-or-minus-180 flip that every replan produces normally; snapping at the antipode was tried
and reverted. The same-direction turn total was absent from the first version, and without it
the dogleg detour in `wd.bridgeStepTwoBypassNoPlace` — where the centre of mass lies east while
the route runs north, holding the error at a constant 90 degrees — was read as an orbit, the
smoothed aim was pulled towards the centre of mass, and the body walked off the platform to
block its own shortcut. That is precisely the trap the smoothing exists to prevent. A detouring
body turns once at the corner and then goes straight; only an orbiting body turns the same way
every tick. The accumulator is cleared only by a reversal or by the body stopping; an error
briefly under 45 degrees merely fails to count, because the raw bearing sweeps through as the
body passes its goal. An earlier version cleared on that, and a 929-tick orbit in
`wd.clientFlowingTrenchPlaceOut`, winding 1200 degrees of yaw, never triggered.

## Where to look

- `bot/pathfinder/moves/SurfaceClimbOut.java` — the exit edge and its three legal shapes.
- `bot/pathfinder/WorldView.java` — `surfaceWaterNodes()` and `diveSearch()`.
- `bot/movement/WalkerTickDrive.java` — the cruise state machine; its constants are in
  `bot/movement/WalkerConstants.java`.
- `bot/movement/WalkerTickClimb.java` — `pillarTakeoverTick`, the executor half of the exit.
- `bot/movement/WalkerTickAim.java` — the orbit break.
- `bot/BotConfig.java` — `pathfinderSurfaceWaterNodes`, `walkerSurfaceSprintSwim`,
  `pathfinderDeepWaterPriced`, `waterDangerPenalty`, `walkerOrbitBreaksAimLag`.

The scenes that hold this behaviour are the `wd.client*` water family; `wd.clientOpenWaterCross`
is the cruise, and the bank climb-out scenes are the exit edge. They run only on a topology with
a real client, because the behaviour is client-side.

The server flag echo described above was found by attaching a debugger to a running client and
logging every change to the sprint flag. That is a delicate thing to do to a body that is in
water — suspending the client thread leaves the integrated server running, and the body drowns
within seconds — so read `docs/dev/debugging.md` before trying it.
