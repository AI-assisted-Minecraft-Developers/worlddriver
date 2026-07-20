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

## Step plan (each step: compile + t0-fabric; milestone: all three gates)

- **Step A ✅ (`ee815ed`)**: replace the flat ctx with the four typed products above.
  Only the per-phase boundary blocks change (rehydrate/persist ↔ product
  construction/reads); bodies keep their locals. Per-tick data flow is fully
  exercised by the suite every tick — the net covers this step well.
- **Step B (pilot ✅ `03ee665` — SearchGovernors; continue family-by-family)**:
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

  Remaining loose-field queue (each still needs its own audit): the waterClimb
  climb-out mega-family (~19 fields, Climb), the arc-length shadow
  (`arcShadow*`/`arcProj`/`arcWedge*`/`arcProg*`, Walker+Progress+StallDetect),
  the search/commit family (`pathBestEffort`, `commitEnd`, `searchFromEnd`,
  `frontierWaitTicks`, `quickCooldown`, `noPathWaitTicks`, `pendingSegment`,
  `searchSuppressedPlace`), the water anti-spin trio
  (`bestGoalDist`/`repathsNoProgress`/`churnResets` — cross-goal!), pillar-edge
  (`pillarStep`/`pillarSinceJump`), and the core follow spine
  (`path`/`edges`/`step`/`stuckTicks`/... — likely stays on Walker).
- **Step C (optional)**: further splitting of Drive/Climb/Aim bodies — only if
  a natural seam presents; task#54 proved the aim pipeline's cross-tick field
  web does not survive hard extraction.

## Non-goals

- No behavior changes; no flag-default changes.
- Not fighting the task#82 per-move Movement direction (AscendMovement et al.)
  — the phase pipeline stays the outer shell those machines plug into.
