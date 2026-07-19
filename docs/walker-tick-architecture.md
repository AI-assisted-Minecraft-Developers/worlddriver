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
- **Step C (optional)**: further splitting of Drive/Climb/Aim bodies — only if
  a natural seam presents; task#54 proved the aim pipeline's cross-tick field
  web does not survive hard extraction.

## Non-goals

- No behavior changes; no flag-default changes.
- Not fighting the task#82 per-move Movement direction (AscendMovement et al.)
  — the phase pipeline stays the outer shell those machines plug into.
