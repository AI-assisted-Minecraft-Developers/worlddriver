# Coverage exemptions (task#95b)

Companion to the coverage campaign (`scripts/coverage/report.py` + `triage.py`).
Every entry documents a Walker-execution branch family that the testkit arenas
**cannot** exercise, why that is structural (not laziness), and where the branch
IS exercised instead. Anything not listed here and not covered by a scene is
open scene debt, not an exemption.

Rule of thumb: an exemption must name a *mechanism* that makes the branch
unreachable in the arena topology — "hard to rig" is not an exemption.

| Family | Where | Why arena-unreachable | Covered instead by |
|---|---|---|---|
| DIG-hold observers (`DIG-slow`, `DIG-dropped`) | `WalkerExpectAlarms.tick` | DEDICATED-server avatar digs are **instant** (1 tick per block, no hold) — measured in the `ad.expectAlarm*` rig series (v3: a plank wall block vanished after `heldSeen=1` and the walker stepped over the stub). A ≥5-tick held dig can never exist in this topology. | Live client journeys (hold-to-mine is the client body's physics); the alarms' original C28-J1 evidence base. |
| `GEAR-degraded` sentinel | `WalkerExpectAlarms.ClientGearCheck` | References `LocalPlayer` — the class does not exist in a dedicated-server JVM (running the flag server-side crashed until the side guard landed; see 2026-07-19 fix). | Live client runs with `walkerExpectAlarm=true`. |
| `pathfinderFrontierCommit` wait/arrive branches | `Walker.frontierHoldOrArrive`, `WalkerTickSearch` | Needs a search that dies at an **unloaded-chunk frontier**; testkit slots force-load their whole grid window, so no frontier exists. | Live long-distance journeys (wjourney runs routinely cross load borders). |
| Creative-flight descent (`descending` latch) | `WalkerTickPrelude` | Requires a player in creative flight; server avatars never fly (abilities off). | Live `mc.debug`/creative sessions. |
| Replay-mode guards (`replayMode` skips) | several phases | `beginReplay` runs them, but the *guard-off* half of each branch pair only differs under live `mc.debug.replay` cadence; the arena replay (`ad.replayRoundTrip`) covers the ON side. | `mc.debug.replay` live tooling. |
| `walkerDebug` log lines inside untriggered mechanisms | all phases | The log line is only reachable when its *enclosing mechanism* triggers; the debug flag itself is exercised (`ad.debugSweepCourse`, plus most water arenas run with debug on). Each such line is covered or exempted **with its mechanism**, not as "logging". | n/a (follows the mechanism's row) |

## Known-untestable-today, candidates for future rigs (NOT exempt)

- Deep-water bank-dig sub-branches (`walkerFutileBankDigRelease`,
  `walkerBankDigForwardExit`, `walkerPillarSurfacePlace`): the waterbank family
  covers the happy paths; the futile/overhang releases need overhang rigs.
  **Six rig versions were attempted and each was defeated by a different real
  mechanism (2026-07-19, wave-4)** — the engage signature ("planner routes a
  climb-out the body can't execute") is structurally hard to pin on a dedicated
  server:
  1. Stone shell + `allowBreak` → the escalated searches planned dig-dives
     THROUGH the pool floor (52k-node best-efforts); instant digs breached the
     basin and the bot fell to y=−280. *Everything structural must be bedrock.*
  2. Open pool rim → the bot climbed the rim and walked off the slot into the
     void (arena void-fall family). *Fence every rig bordering void.*
  3. Mutating the bank to an unroutable bedrock overhang → no climb edge in any
     path → `wantClimb` never held → the bank-dig never engaged; the bot just
     swim-churned.
  4. Static routable +2 bank (riser scan latches `isSolid` bedrock, which does
     survive instant digs) → the server body simply MOUNTS +2 from water
     (ARRIVED in 139t) — "routable" and "mountable" coincide in this topology.
  5. 1-wide canal + unroutable +3 bank (floatingBankRam arm) → the DRY
     futile-search cap FAILED the run on the west shore in 93t (the water
     exemption only applies once afloat).
  6. Same + cap headroom → the walker machine-gunned 60 exhausted searches on
     the 17-cell graph and never entered the water at all.
  The live signature (−784: drift under an overhang while the path climbs
  elsewhere) is execution-drift-born; a deterministic arena reproduction likely
  needs either a scripted-path harness (bypass A*) or the client body (hold-to-
  mine + weaker swim-jump). Until then these branches are live/replay-covered
  only.

## Wave-3 closures (2026-07-19) + rig-design constraints they exposed

Covered by `ad.boxedChurnEscalate`, `ad.quickStartStub`, `ad.ascentRamSlideBack`,
`ad.aboveNodeStallPitFill`, `ad.verticalResyncSlideBack`, `ad.stepUpBackoffCeiling`:
the anti-churn window family (+ both window-shortening flags + sticky escalation +
escalated `pf*` getters), the pre-path stub family (land bee-line reject →
`tryQuickStart`), and all four steep-ascent ram/stall recovery folds.

Constraints any future arena rig must respect (each defeated 2–3 rig versions,
probe-proven via `Walker.progressProbe()` embedded in ctx.fail):

1. **Adoption-radius wall-snap**: `adoptPath`/fast-forward measure euclidean
   "near the feet", not connectivity — any alternate route within ~3–4 blocks
   THROUGH a thin wall snaps the carrot across the bedrock and pins the bot on
   the wrong side indefinitely. Walls between "inside" and "the route out" must
   be thicker than that radius, or the route must not exist until a mutation
   opens it.
2. **Convex-corner dead zone**: a sharp bedrock corner between the bot and its
   next node puts the drive in a reCentre/carrot micro-orbit (§39 family) that
   outlives scene budgets. Prefer water (no corners to catch) or straight-line
   releases over wall gaps.
3. **Idle-slice floor**: with `path==null` the search runs at
   `max(pathfinderSliceMs, pathfinderIdleSliceMs)` — pinning only `sliceMs`
   does NOT keep a search in flight (the 30 ms idle slice finishes small arena
   graphs in one tick).
4. **Futile-search cap races slow mechanisms**: in a sealed rig the gap#49-③
   5-search cap FAILs the run before a 240t churn window fires; scenes probing
   slow machinery must raise `walkerFutileSearchCap`.
5. **Partial-width stairs shed the bot**: the drive's lateral drift slides the
   body off a stair strip's side face into gutter lanes; staircases the bot MUST
   climb should span the full approach width, and post-mutation recovery stairs
   should be goal-aligned so the climb isn't dragged diagonally off an edge.
