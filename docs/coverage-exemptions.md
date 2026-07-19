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

- Boxed-pocket churn escalation (`pathfinderBoxedEscalate` arming): needs a
  CHURN_WINDOW (~400t) of confirmed net-zero displacement in a sealed pocket —
  rig is possible (bedrock box + far goal) but slow; scene budget ~1200 ticks.
- Progressive quick-start (`tryQuickStart` stub adoption): needs a search that
  stays in flight across ticks (`pathfinderSliceMs` pinned ~0–1) while the bot
  holds; rig possible with a large far-goal arena.
- Steep-ascent ram recovery folds (`walkerAscentRamJitterImmune`,
  `walkerVerticalResync`, `walkerStepUpBackoffRetry`, `walkerAboveNodeStallRecover`):
  need sustained physical ram geometry; candidate rig = mid-run world mutation
  (the `ad.expectAlarmBlockedJump` ceiling pattern) that invalidates a committed
  climb without opening a reroute.
- Deep-water bank-dig sub-branches (`walkerFutileBankDigRelease`,
  `walkerBankDigForwardExit`, `walkerPillarSurfacePlace`): the waterbank family
  covers the happy paths; the futile/overhang releases need overhang rigs.
