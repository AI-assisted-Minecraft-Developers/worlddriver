# Coverage exemptions

Companion to the branch-coverage tooling in `scripts/coverage/` (`report.py` merges JaCoCo
execution data and digests it per class; `triage.py` sorts the uncovered lines). Every
entry below names a family of walker-execution branches that the scene arenas **cannot**
exercise, explains why that is structural rather than an omission, and says where the
branch is exercised instead.

Anything not listed here and not covered by a scene is open scene debt, not an exemption.
An exemption must name a *mechanism* that makes the branch unreachable in the arena
topology. "Hard to rig" is not an exemption.

## Exempt branch families

| Family | Where | Why the arenas cannot reach it | Covered instead by |
|---|---|---|---|
| The dig-hold observers, `DIG-slow` and `DIG-dropped` | `WalkerExpectAlarms.tick` | These watch a break action *held* across ticks. A dedicated-server body's dig completes in a single tick, so a hold of the length the alarms look for cannot exist in that topology. | Runs with a real client body, where mining is a held action. |
| The `GEAR-degraded` sentinel | `WalkerExpectAlarms.ClientGearCheck` | The check reads `LocalPlayer`, a class that does not exist in a dedicated-server JVM. Every reference to it is confined to that nested class precisely so the server JVM never loads it. | Client runs with `walkerExpectAlarm` enabled. |
| The wait and arrive branches of the loaded-chunk frontier commit | `Walker.frontierHoldOrArrive`, `WalkerTickSearch` | They need a search that runs out of known terrain at an unloaded-chunk boundary. A scene's arena force-loads its whole grid window, so no such boundary exists inside it. | Long-distance journeys, which cross chunk-load borders routinely. |
| The creative-flight descent latch | `WalkerTickPrelude` | It requires a player in creative flight. Server-side bodies never fly — the abilities are off. | Live creative sessions and the debug tooling. |
| The guard-off half of the replay-mode skips | Several phases | `beginReplay` runs the guarded branches, but the *unguarded* half of each pair only differs under the live replay cadence. The arena replay scene covers the guarded side. | The live `mc.debug.replay` tooling. |
| Debug log lines inside mechanisms that did not trigger | All phases | A log line is only reachable when its enclosing mechanism fires. The debug flag itself is exercised by scenes that run with it on. Each such line is covered or exempted **with its mechanism**, never as "logging". | Follows its mechanism's row. |

## Not exempt: known-untestable today, candidates for a future rig

The deep-water bank-dig release branches — the ones behind
`walkerFutileBankDigRelease`, `walkerBankDigForwardExit` and `walkerPillarSurfacePlace` —
are covered on their happy paths by the water-bank scene family, but the futile and
overhang releases are not. They are listed here as debt rather than as exemptions, because
nothing about the topology makes them structurally unreachable; they are merely very hard
to stage.

The engage signature is "the planner routes a climb-out that the body cannot execute", and
six rig designs were each defeated by a different real mechanism:

1. A stone shell with breaking allowed: the escalated searches planned dig-dives through
   the pool floor, the instant digs breached the basin, and the body fell out of the
   world. Anything structural in such a rig must be bedrock.
2. An open pool rim: the body climbed it and walked off the arena into the void. Fence
   every rig that borders void.
3. A bedrock overhang mutated to be unroutable: with no climb edge in any path the climb
   intent never held, so the bank-dig never engaged and the body just churned in the water.
4. A static routable bank two blocks up: the riser scan latches onto solid bedrock, which
   survives instant digs, and the server body simply mounts it. In this topology "routable"
   and "mountable" coincide.
5. A one-wide canal with an unroutable bank three blocks up: the dry futile-search cap
   failed the run on the shore, because the water exemption only applies once afloat.
6. The same with headroom added: the walker exhausted search after search on a graph of
   seventeen cells and never entered the water at all.

The live signature is execution drift — the body drifts under an overhang while the path
climbs elsewhere — so a deterministic arena reproduction probably needs either a
scripted-path harness that bypasses A*, or a client body with its slower mining and weaker
swim jump. Until one of those exists, these branches are covered only by live runs and
replay.

## Constraints any future rig must respect

Each of these defeated two or three rig versions before being understood, and each was
proven with the walker's own progress probe embedded in the failure message.

1. **Path adoption measures distance, not connectivity.** Adoption and fast-forward ask
   whether an alternate route passes near the feet in a straight line. A route on the far
   side of a thin wall, within a few blocks, snaps the target across the bedrock and pins
   the body on the wrong side indefinitely. Walls between "inside" and "the way out" must
   be thicker than that radius, or the route must not exist until a mutation opens it.
2. **A convex corner is a dead zone.** A sharp bedrock corner between the body and its
   next node puts the drive into a re-centring micro-orbit that outlives a scene's tick
   budget. Prefer water, which has no corners to catch on, or a straight-line release over
   a gap in a wall.
3. **There is a floor on the search slice.** With no path in hand the search runs at the
   larger of the ordinary slice and the idle slice, so pinning only the ordinary slice
   does not keep a search in flight — the idle slice finishes a small arena graph in one
   tick.
4. **The futile-search cap races slow mechanisms.** In a sealed rig the cap fails the run
   before a long anti-churn window has a chance to fire. A scene probing slow machinery
   has to raise `walkerFutileSearchCap`.
5. **Partial-width stairs shed the body.** The drive's lateral drift slides the body off
   the side face of a narrow stair strip. A staircase the body must climb should span the
   full approach width, and a recovery staircase revealed by a mutation should be aligned
   with the goal so the climb is not dragged diagonally off an edge.
6. **Rim every floor that borders void, even after consecutive clean runs.** A best-effort
   route can wrap the exterior of the arena's bedrock mass and walk the body off the
   platform edge. This was observed after five consecutive clean runs of the same scene. A
   three-block-tall bedrock perimeter, placed outside the adoption radius of rule 1, makes
   the scene deterministic against it.
