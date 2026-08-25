# task#96 — WalkerTick* aggressive refactor: design + execution log

Goal (user directive 2026-07-19): the task#94 mechanical split left 9 phase
classes wired through a flat 31-field `WalkerTickCtx` with uniform
rehydrate/persist boilerplate. Turn that seam into REAL architecture on top of
the task#95 coverage net (bot.* branch 61.4%, all three gates GREEN).

## Measured inter-phase data flow (2026-07-19 census)

```
Prelude      out: p, foot, searchFoot
StallDetect  in : p, foot                     out: d, offPath, wedged, fellOffPath, fellBelowRoute, breakingEdge
Repath       in : ^ + searchFoot              out: (breakingEdge)
Search       in : p, foot, d
Progress     in : p, foot
Climb        in : p, foot                     out: edge
EdgeGuards   in : p, foot, edge               out: wp, parkourEdge, bridging, placingEdge, steppingOffFall, steppingOffWaterFall
Aim          in : ^                           out: aimYaw, aimSrc, aimAtWaypoint, reCentre, trendCam, descentNodeYaw,
                                                   dryDescent, flatWaterTrend, diveUnderCap, diving, stepColDx/Dz,
                                                   stepUpFreeze, pivotForStepUp, descendBrake
Drive        in : everything                  out: (terminal)
```

Three natural products: **StallVerdict** (StallDetect → Repath/Search),
**EdgeState** (Climb/EdgeGuards → Aim/Drive), **AimPlan** (Aim → Drive), plus a
**TickFrame** (p/foot/searchFoot from Prelude).

### The census is now enforced, not just recorded (2026-07-27)

That table above was hand-derived, and nothing stopped the code from drifting
away from it. `common/src/test/.../movement/WalkerTickDataflowTest` re-derives it
from source on every `:common:test` and fails if the flow stops being strictly
forward. It reads the phase ORDER out of `Walker#tickInner` rather than hardcoding
it, so reordering the pipeline re-checks every field against the new order instead
of silently invalidating the test.

What it catches, in the order the failures matter:

- **read-before-write** — a phase reading a product an earlier phase never wrote.
  This is the expensive one because it is silent: read `cx.aim.diving` from
  EdgeGuards and you get `false` every tick forever. No exception, no log, just a
  guard that never fires.
- a ctx field **no phase writes** (every reader gets the zero value), or one **no
  later phase reads** (it should be a local in its producer, not a ctx field).
- the phase files on disk and the phases `tickInner` actually calls **disagreeing**.

Verified 2026-07-27 by injecting each defect and confirming a red: the
read-before-write control reports `aim.diving: read by EdgeGuards (phase 6) but
first written by Aim (phase 7)`.

**One value legitimately crosses a mid-pipeline path swap.** `WalkerTickProgress`
can replace the whole path (`adoptPath` at the splice, or a `tryQuickStart` /
`tryLandBeeline` / `tryWaterBeeline` stub) and fall through with `step=1` on the
NEW path. Everything downstream re-derives: `edges.edge` comes from
`wk.edgeAt(wk.step)` in Climb, `edges.wp` from EdgeGuards, both after the swap;
`frame.foot` stays valid because no phase moves the body (the position only
changes at the physics step after the pipeline). The exception is
`stall.breakingEdge`, computed by StallDetect from the OLD path's edge and read by
Drive, which pairs it with the new `edge`/`wk.path.get(wk.step)` in
`MovementContext`. It feeds only the ascent dead-zone watchdog exemption, for one
tick, at the start of a fresh episode — not enough to move that watchdog's verdict.
Left alone deliberately: `WalkerTickDrive` is flagged state-machine surgery, and
this is a one-boolean discrepancy with no live evidence behind it. Recorded here so
it stays a known quantity rather than a rediscovery.

## Step plan (each step: compile + t0-fabric; milestone: all three gates)

- **Step A ✅ (`e441692`)**: replace the flat ctx with the four typed products above.
  Only the per-phase boundary blocks change (rehydrate/persist ↔ product
  construction/reads); bodies keep their locals. Per-tick data flow is fully
  exercised by the suite every tick — the net covers this step well.
- **Step B (pilot ✅ `db2eb5d` — SearchGovernors; continue family-by-family)**:
  migrate phase-private Walker fields into per-phase state holders. The 2026-07-19
  census found ~31 candidates BUT spot-checks showed false positives
  (`hColRamTicks` is read by Walker's carrot logic; `churnBase` has a
  goal-reset site in Walker.java). Every move must audit Walker's reset blocks
  — cross-goal persistence is NOT covered by single-goal scenes, so a missed
  reset is a net-invisible regression. Do only with per-field greps.
- **Step B2–B7 ✅ (2026-07-19, each batch t0-fabric-gated)** — twelve more
  families landed, every one per-field audited (all phase files + Walker bare
  names + BOTH reset blocks) with the measured reset semantics written into the
  holder javadoc:
  - `DrownGuard` (Climb): reset clears latch+turnTicks only; heading is scratch,
    probe PERSISTS across goals (rotating pocket probe).
  - `StepUpBackoff` (Drive arms / Repath drives): reset clears ticks+cooldown;
    yaw scratch.
  - `DiveLatches` (Aim): both latches cleared by both journey resets.
  - `Unstuck` (Repath counts / StallDetect shoves): NOT touched by forceRepath
    (wedge memory must survive a process resume); `resetForNewGoal()` in setGoal
    only; `dropWedgeAnchor()` for stub-adoption/post-burst; Climb's dig-start
    partial clear stays a direct write. Renamed `burstTicks`/`burstYaw`.
  - `BoxedChurn` (StallDetect; carrot reads hColRamTicks): `resetForNewGoal()`
    in setGoal only — forceRepath never cleared the window; hColRamTicks has NO
    reset site anywhere (self-zeroes each collision-free tick).
  - `EscalationClock` (Prelude ticks it; StallDetect + pinch check arm it):
    `armed()`/`arm()`/`disarm()` replace three hand-rolled
    `pfTickCounter < boxedEscalateUntilTick` comparisons; tick is monotonic and
    never reset.
  - `PillarRecover` (StallDetect engages / Drive drives / Climb gates): reset
    clears the latch only — cell/peakY/stallTicks are latch-gated.
  - `AimSmoothing` (Aim; waterDriveYaw read by Drive, lastAimYaw by Climb):
    reset resyncs the EMA chain + yaw-thrash detector (the exact four fields
    both journey resets cleared); the rest self-manage.
  - `DriveLatches` (Drive): reset clears the two sprint-brake latches only;
    debouncers (underwaterTicks, climbPressConsec, descentDriveRejectStreak)
    self-manage.
  - `StepProgress` (Progress updates; StallDetect/Aim/Drive verdicts;
    Prelude forwards noStepProgressTicks to the alarms): `restartWindows()` for
    the two journey resets; adoptPath keeps its DIFFERENT partial restart
    (stuckStepHigh = step−1, not −1) as direct writes, ditto
    beginScriptedFollow's baseline force.
  - `RamFold`, `PhysicalStall`, `StickyDig`: fully self-managing families —
    no journey reset ever touched them (stickyDig release is world-state-driven).

- **Step B8–B10 ✅ (2026-07-19, per-batch t0-fabric-gated)** — the queue is drained:
  - `ArcShadow` (B8): projection + monotonic shadow + ram-wedge + net-progress
    window; updated only by `arcShadowTick`, NO journey reset (self-resets on
    path-identity change). `PillarEdge` (B8): the in-progress pillarUp pair.
  - `GoalSpin` (B9): the water anti-spin — `churnResets` now lives IN the family
    but OUT of its reset (cross-goal budget, documented on the holder).
  - `SegmentCommit` (B9): in-flight sliced A* + best-effort segment plumbing
    (activeSearch/pathBestEffort/commitEnd/searchFromEnd/pendingSegment/
    searchSuppressedPlace/frontierWaitTicks); `reset()` = the four fields both
    journey resets cleared. `SearchGovernors` grew quickCooldown/noPathWaitTicks
    (setGoal-only reset preserved — verified the method has exactly one call
    site; forceRepath's quickCooldown-only clear stays a direct write).
  - `WaterClimb` (B10): the 20-field climb-out machine in one holder (Climb-owned;
    `digging` read by Search/StallDetect). `reset()` = the 14 fields both journey
    resets cleared; the LOCKED engage column/heading (targetY/colX/colZ/yaw),
    lastDigAimEyeY and digGroundedStreak stay out (latch-gated/self-managing).

  **End state**: Walker.java 1409 lines (was 5849 pre-task#94), 19 typed state
  holders; 29 loose declarations remain and all are deliberate — the follow
  spine (path/edges/step/stuckTicks/tick counters), goal/status reporting, and
  true singletons (descending, forceFellOffPath, freeHangDriveYaw,
  surfaceWaterLatch, dbg pair).

## Incident log

- 2026-07-19 t0 RED `wd.boxedChurnEscalate` on the B9 tree: bot drifted to
  dz-8.6 in the WEST chamber, off the unfenced platform edge, fell to the
  dogfood ground (y=-60) before the churn release could fire → timeout.
  Attribution: identical-tree rerun GREEN + B9 proven write-equivalent (reset
  call-site audit) → known arena void-fall lottery, not the refactor. Fix: 3-tall
  bedrock perimeter rim on the scene (≥6 blocks from the pocket, outside the
  carrot wall-snap radius). Rule: a coverage scene whose floor borders void MUST
  be rimmed even when five consecutive runs never wandered — the lottery tail is
  real.
- **Step C (optional)**: further splitting of Drive/Climb/Aim bodies — only if
  a natural seam presents; task#54 proved the aim pipeline's cross-tick field
  web does not survive hard extraction.

## Non-goals

- No behavior changes; no flag-default changes.
- Not fighting the task#82 per-move Movement direction (AscendMovement et al.)
  — the phase pipeline stays the outer shell those machines plug into.
