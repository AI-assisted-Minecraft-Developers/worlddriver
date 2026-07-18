# Testkit migration log — migrate-then-delete drift record

**Spec §10 drift-log.** This file is the permanent, auditable record of the
mc-testkit *migrate-then-delete* policy: as legacy `@GameTest` arenas are
re-implemented as dogfooded `ad.*` testkit scenes (in
`common/src/testmod/.../scene/AgentDriverScenes.java`), their now-redundant
legacy twins are deleted from the neoforge legacy suite
(`neoforge/src/testmod/.../AgentGameTest*.java`) in bounded waves. Each deletion
wave is recorded here — one row per deleted legacy test method — so the shrink
of the legacy registered count is never silent and every retirement is traceable
back to the scene that replaced it and the commit that migrated it.

## Policy — why migrate-then-delete, and the deletion precondition

A migrated scene and its legacy twin are kept side by side (a *dual-gate A/B*)
until the scene is proven a byte-faithful replacement. The deletion precondition
(established P1c, TODO/`e69e547`) is: **three consecutive dual-gate greens AND an
external `ad.*` expectation gate** (`--expect-file` canonical manifest) must both
hold before any twin is retired — otherwise a ServiceLoader break could drop the
`ad.*` scenes from *registered* and *executed* simultaneously, a self-consistent
false-GREEN (#85 at suite-composition level). That precondition was satisfied
across the P1.6 dual-loader determinism matrix and the repeated P1.6/P2/P3
dogfood acceptance runs (every `ad.*` scene byte-identical across fabric ×3 +
neoforge ×3, and gated by `--expect-file scripts/testkit/expected-scenes-*.txt`),
so this wave executes the first retirements.

Deletion never closes the *engine* task a scene guards — see the non-closure note
below.

## Wave 1 (P4a Task 4) — the 8 migrated `ad.*` twins retire

**Count arithmetic: legacy registered 130 → 122** (−8 `@GameTest` methods). The
reconcile (`scripts/gt_reconcile.py`) is fully dynamic — it counts the manifest,
no hardcoded total exists in `gt_reconcile.py` or `run_gametests.sh`, so no script
constant needed updating this wave. Three now-orphaned *helpers* were deleted
alongside the twins (they do not affect the registered count): `ascendCtx`
(private, used only by `ascendDeadZoneWatchdogArena`) and the public delegates
`probeSwing` / `probeHurt` (P1.6 cross-package promotions whose sole caller was
`serverAvatarGearScopeProbeArena`; the scene calls `SimProbes.probeSwing/probeHurt`
directly, so the delegates were dead once the twin left). The `ad.gearScope`
scene javadoc's two `@link` targets were repointed from
`AgentGameTestServer#probeSwing/#probeHurt` to `SimProbes#probeSwing/#probeHurt`
to keep the reference live.

`clearBox` was **kept** — it is shared by many surviving Server arenas (only the
gearScope call site went away).

| deleted legacy test method | legacy class | ad.* scene | migration commit | this deletion | notes (failure-set member?) |
|---|---|---|---|---|---|
| `ascendMovementNoopArena` | AgentGameTestTerrain | `ad.ascendMovementNoop` | `b86468d` (P1c wave 1, swallowed trio) | `1f30322` | not a failure-set member (passed) |
| `ascendDeadZoneWatchdogArena` | AgentGameTestTerrain | `ad.ascendDeadZoneWatchdog` | `b86468d` (P1c wave 1, swallowed trio) | `1f30322` | not a failure-set member (passed) |
| `diagonalAscentSpeedArena` | AgentGameTestTerrain | `ad.diagonalAscentSpeed` | `b86468d` (P1c wave 1, swallowed trio) | `1f30322` | not a failure-set member (passed) |
| `selfShaftDigUpArena` | AgentGameTestTerrain | `ad.selfShaftDigUp` | `c5b3187` (P1.5a wave 2a) | `1f30322` | **lottery-family** `selfshaftdiguparena` — deterministic solo-RED (worstBackslide 20.252203415101263); guards **task#86** (gap #53) |
| `descentYawArena` | AgentGameTestTerrain | `ad.descentYaw` | `674179e` (P1.5a wave 2a) | `1f30322` | **lottery-family** `descentyawarena` — byte-determinism-sensitive (P0 probe-accident victim) |
| `serverAvatarGearScopeProbeArena` | AgentGameTestServer | `ad.gearScope` | `76db598` (P1.5b wave 2b) | `1f30322` | **lottery-family** `serveravatargearscopeprobearena` (gap #46 gear-scope probe) |
| `serverMineBuriedOreArena` | AgentGameTestServer | `ad.buriedOre` | `0b2e661` (P1.5b wave 2b) | `1f30322` | **lottery-family** `buriedore*` (gap #60 buried-ore reachability) |
| `entityLeashRepathArena` | AgentGameTestServer | `ad.entityLeash` | `916579f` (P1.5b wave 2b) | `1f30322` | **lottery-family** `entityleashrepatharena` — master-inherited solo-RED (phase 2 y≈−60 void-fall); guards **task#87** |

Orphaned helpers deleted in the same commit (no registered-count effect):

| deleted helper | legacy class | reason orphaned |
|---|---|---|
| `ascendCtx` (private) | AgentGameTestTerrain | sole caller `ascendDeadZoneWatchdogArena` deleted |
| `probeSwing` (public delegate) | AgentGameTestServer | P1.6 promotion; sole caller `serverAvatarGearScopeProbeArena` deleted; scene uses `SimProbes.probeSwing` |
| `probeHurt` (public delegate) | AgentGameTestServer | P1.6 promotion; sole caller `serverAvatarGearScopeProbeArena` deleted; scene uses `SimProbes.probeHurt` |

### `ad.settingRegistryClosed` — new scene, zero twins (recorded honestly)

`ad.settingRegistryClosed` (P2a, `6de3d97`) is a **new** dogfood scene, not a
migration of any legacy `@GameTest`. It has **no legacy twin**, so nothing is
deleted for it this wave. Recorded here so the wave's provenance is complete: 9
migrated scenes total, 8 with legacy twins (retired above), 1 net-new (0 twins).

## Deletion-effect on the legacy failure set

Before this wave the documented legacy lottery / stable-core failure family was:

```
{ serveravatargearscopeprobearena, descentyawarena, vineoverwaterclimbarena,
  selfshaftdiguparena, entityleashrepatharena, buriedore*, deepwatercross*,
  agentrpcsmoke }
```

Five of those names are twins retired above
(`serveravatargearscopeprobearena`, `descentyawarena`, `selfshaftdiguparena`,
`entityleashrepatharena`, `buriedore*`) — they are **gone** and must NOT reappear
in a post-deletion run. The permitted surviving failure set is therefore the
family **minus the deleted names**:

```
⊆ { vineoverwaterclimbarena (optional, −711 live bug),
    deepwatercross* , agentrpcsmoke (required) }
```

Any name outside that set, or any deleted name reappearing, is a STOP/BLOCKED
condition.

**Post-deletion result (2026-07-18).** `scripts/run_gametests.sh` reconciled
`registered=122 entered=122` with **0 swallowed / 0 drifted**, **all 122 required
tests PASSED**, and a **single optional failure `vineoverwaterclimbarena`** (the
−711 live bug, in the permitted survivor set). No deleted name appears anywhere in
the manifest or log. **Milestone:** the five required stable-core lottery-family
failures were exactly the deleted twins, so the *required* legacy suite is now
fully green for the first time. (A first launch hit the documented intermittent
`ChunkMap.processUnloads` livelock in the untouched, re-entrant-`level.tick`
`serverForbidDigWallArena` — recovered by explicit-PID kill + world wipe + rerun;
full evidence and thread dump in `.superpowers/sdd/task-4-report.md` §3b.)

## Wave 2 (P4b Task 1) — the Terrain family: 12 migrated + deleted, 1 retired-without-scene (controller-adjudicated)

**Count arithmetic: legacy registered 122 → 109** (−13 `@GameTest` methods — 12 migrated
twins deleted + 1 retired-without-scene deleted). This is the FIRST P4b family wave and
defines the wave protocol Tasks 2–4 replicate: per-name direct translation + A/B →
dual-loader ×2 dogfood result-set identity → same-commit twin deletion + this log row →
legacy reconcile. The reconcile (`gt_reconcile.py`) is fully dynamic (counts the manifest),
so no script constant needed updating. `AgentGameTestTerrain` is now empty and was DELETED
(class file removed, its `AgentGameTestRegistrar` line removed — it self-registered via both
`@GameTestHolder` auto-scan AND the explicit Registrar list).

**`descentDriftArena` — FULL RETIREMENT, controller-adjudicated (2026-07-18).** 12 of the 13
Terrain tests migrated to `ad.*` scenes; the 13th, `descentDriftArena`, was raised as a
retired-without-scene candidate (P4b escape hatch) and the controller **accepted full
retirement and directed deletion of the twin**. Grounds (verbatim):

1. **Documented PROVEN FALSE GREEN (gap #49) superseded by its own live A/B** — its green
   asserts nothing; negative-value coverage. (Legacy `required = false`; it "passed" the
   shared-body suite only because a concurrent arena shoved the shared FakePlayer out of the
   wedge. SOLO it is deterministically RED — fix-ON leg still LAUNCHES off the stair into open
   void, minY≈−60. Its own javadoc: "the fix's gate is the LIVE A/B".)
2. **Faithful migration physically impossible** — the RED path is a >60 s single-tick A* churn
   that trips the `ServerHangWatchdog` and kills the dogfood harness (crash archived:
   `neoforge/run-dogfood/crash-reports/crash-2026-07-18_12.58.53-server.txt`, stack rooted at
   `AgentDriverTerrainScenes.descentDrift → Walker.tick → PathFinder$Search.advance`). Any
   bounded rewrite either breaks determinism (wall-clock ms cap → non-deterministic, fails the
   ×2 result-set identity gate) or silently rebaselines a broken rig (trimming
   `pathfinderMaxNodes`), both banned.
3. **Descent-behaviour coverage remains guarded** by `ad.descentYaw`'s golden signature gate.

| deleted legacy test method | legacy class | ad.* scene | migration commit | this deletion | notes (first-run A/B verdict) |
|---|---|---|---|---|---|
| `summitArena` | AgentGameTestTerrain | `ad.summit` | this commit (P4b wave 2) | this commit | identical — PASS both loaders ×2 (canopy `toBreak` fix + pillar-to-goal) |
| `sheerWallArena` | AgentGameTestTerrain | `ad.sheerWall` | this commit (P4b wave 2) | this commit | identical — PASS both loaders ×2 (+5 sheer wall climb) |
| `bridgeGapArena` | AgentGameTestTerrain | `ad.bridgeGap` | this commit (P4b wave 2) | this commit | identical — PASS both loaders ×2 (3-cell void bridge) |
| `parkourAscendArena` | AgentGameTestTerrain | `ad.parkourAscend` | this commit (P4b wave 2) | this commit | identical — PASS both loaders ×2 (sprint-jump +1 landing, no pit) |
| `ridgeOvershootArena` | AgentGameTestTerrain | `ad.ridgeOvershoot` | this commit (P4b wave 2) | this commit | identical — PASS both loaders ×2 (fall-overshoot smoothness, maxNoProgress≤80) |
| `stepUpCrestOrbitArena` | AgentGameTestTerrain | `ad.stepUpCrestOrbit` | this commit (P4b wave 2) | this commit | identical — PASS both loaders ×2 (held-pose A/B: OFF wedges, ON advances) |
| `descentArena` | AgentGameTestTerrain | `ad.descent` | this commit (P4b wave 2) | this commit | identical — PASS both loaders ×2 (12-step 1-wide descent, no pit) |
| `ascentSpeedArena` | AgentGameTestTerrain | `ad.ascentSpeed` | this commit (P4b wave 2) | this commit | identical — PASS both loaders ×2 (ascent b/s floor 1.5) |
| `ledgeOvershootArena` | AgentGameTestTerrain | `ad.ledgeOvershoot` | this commit (P4b wave 2) | this commit | identical — PASS both loaders ×2; `.withChunkRadius(2)` (runway+terrace dx→+44) |
| `wallCollisionProbe` | AgentGameTestTerrain | `ad.wallCollisionProbe` | this commit (P4b wave 2) | this commit | identical — PASS both loaders ×2 (avatar stops at 2-tall wall; `commandForward`, not Walker) |
| `bridgeDescendArena` | AgentGameTestTerrain | `ad.bridgeDescend` | this commit (P4b wave 2) | this commit | identical — PASS both loaders ×2 (descending bridge smoke) |
| `bareHandDigCadenceArena` | AgentGameTestTerrain | `ad.bareHandDigCadence` | this commit (P4b wave 2) | this commit | identical — PASS both loaders ×2 (gap #66 dig cadence, dropsWhileSolid≤2) |

**Retired-without-scene (DELETED — controller adjudicated FULL RETIREMENT):**

| legacy test method | legacy class | status | rationale |
|---|---|---|---|
| `descentDriftArena` | AgentGameTestTerrain (deleted) | **RETIRED-WITHOUT-SCENE — controller adjudicated, twin DELETED** | (1) documented PROVEN FALSE GREEN (gap #49) superseded by its own live A/B — negative-value coverage; (2) faithful migration physically impossible (>60 s single-tick A* churn → `ServerHangWatchdog` kills the dogfood harness, crash archived; bounded rewrites either break determinism or rebaseline a broken rig, both banned); (3) descent coverage remains via `ad.descentYaw`'s golden signature gate |

**No helpers orphaned by name-collision this wave** (unlike wave 1): the shared
`AgentGameTestSupport` helpers the migrated scenes needed (`buildFloor`,
`grantWaterEffects`) are still used by OTHER surviving legacy families (Water/Bias/Core),
so nothing was deleted from `AgentGameTestSupport` even though `AgentGameTestTerrain`
itself is now gone. `buildFloor` was **inlined** into `AgentDriverTerrainScenes`
(origin-relative, promoted-into-class, not imported across the neoforge testmod
source-set boundary); `grantWaterEffects` was reused from the common `SimProbes` single
source.

**Dual-loader determinism (first-run A/B, 2026-07-18).** neoforge dogfood ×2 and fabric
dogfood ×2 (each on a freshly wiped `run-dogfood/world`, servers run sequentially): all
four runs GREEN; the `(name, outcome)` result set is **byte-identical across both runs of
each loader AND across loaders** (23 PASS = 2 builtin + 9 existing `ad.*` + 12 new; plus
the two expected canaries `canaryMustFail`→FAIL / `canaryMustTimeout`→TIMEOUT, and
`canaryMustSwallow` correctly omitted). The existing 9 `ad.*` goldens stayed PASS (golden
bytes intact). No new scene was flaky; no threshold was tuned.

## Wave 3 (P4b Task 2) — the Bias family: 13 migrated + deleted

**Count arithmetic: legacy registered 109 → 96** (−13 `@GameTest` methods, all migrated
twins deleted; **no** retired-without-scene this wave). The reconcile (`gt_reconcile.py`) is
fully dynamic (counts the manifest), so no script constant needed updating.
`AgentGameTestBias` is now empty and was DELETED (class file removed). It self-registered via
`@GameTestHolder` auto-scan **only** — it was **never** in `AgentGameTestRegistrar`'s explicit
list (wave-1 precedent; the Registrar's own class javadoc records this), so no Registrar line
needed removing. New provider: `AgentDriverBiasScenes` (append line to the common
`SceneProvider` service file; `ad.*` names added to BOTH `expected-scenes-{neoforge,fabric}.txt`
in this commit).

**All 13 are PLANNER-ONLY — the brief's "driver-mode (createIsolated)" hypothesis did not
hold.** Unlike wave 2 (Terrain, which drove the real `Walker`), no Bias arena drives a
`Walker` or a registered driver: each builds an immutable `LevelWorldView` over a
`createUnique` avatar and runs one/two `PathFinder` searches, then asserts on the returned
`Result` (constraint/bias gates). So there is no executor flakiness, no per-tick stepping, no
`SimProbes.grantWaterEffects` (nothing moves or takes damage), and the body resolves on the
first RUN tick. The dense `AgentGameTestSupport` coupling flagged in the brief (21 refs) was
20× `gtOnlySkips` (deleted — the testkit gate self-reconciles) + `maxPathY` (inlined faithfully
into the scene class, not imported across the neoforge testmod boundary). The per-arena private
geometry helpers (`pathEntersZone`, `minPathY`, `maxPathXZDist`, `pathEntersWater`,
`minPathZRelative`, `distToWaterLE`, `firstOutOfBand[Smoothed]`) were carried over verbatim.

**Config-pin faithfulness.** Only the 7 scenes whose legacy body had a `try/finally`
`BotConfig` save/restore use `pinnedBaseline()` + `ctx.cleanup(pin::close)` (registered FIRST →
LIFO closes LAST, after avatar discard): `digUpY`, `digDownY`, `columnRadius`, `forbidDig`,
`escapeFarthestNoRockDrill`, `budgetAwayTunnelChurn` (break/place ± walkerDebug), and
`shorelineSmoother` (walkerDiagonalStringPull). The 6 pure-constraint scenes that touched no
config are ported without a pin — faithful, and safe because the harness restores baseline
between scenes so every planner sees clean defaults.

**Origin slots.** 11 take AUTO slots at the default radius; 2 widened to `.withChunkRadius(2)`
because their footprint exceeds the default window's +31 edge: `ad.shorelineHug` (clear span
reaches dx +41) and `ad.shorelineSmoother` (basin reaches dx/dz +32). No scene is pinned to a
fixed slot — every Bias gate is a discrete, integer-cell, position-invariant planner OUTCOME, so
registry-growth relocation cannot flip it (the `ad.buriedOre` auto-slot precedent applies).

| deleted legacy test method | legacy class | ad.* scene | migration commit | this deletion | notes (first-run A/B verdict) |
|---|---|---|---|---|---|
| `avoidRegionDetourArena` | AgentGameTestBias | `ad.avoidRegionDetour` | this commit (P4b wave 3) | this commit | identical — PASS both loaders ×2 (A4b `AvoidRegion` mid-lane detour) |
| `digUpYArena` | AgentGameTestBias | `ad.digUpY` | this commit (P4b wave 3) | this commit | identical — PASS both loaders ×2 (A2b chained PillarUp dig-up to YLevel) |
| `digDownYArena` | AgentGameTestBias | `ad.digDownY` | this commit (P4b wave 3) | this commit | identical — PASS both loaders ×2 (A2b DownBreak straight-shaft dig-down) |
| `columnRadiusArena` | AgentGameTestBias | `ad.columnRadius` | this commit (P4b wave 3) | this commit | identical — PASS both loaders ×2 (`ColumnRadius` ascent containment vs drift) |
| `parkourGateArena` | AgentGameTestBias | `ad.parkourGate` | this commit (P4b wave 3) | this commit | identical — PASS both loaders ×2 (A2a `CapabilityProfile` PARKOUR gate) |
| `yFloorConstraintArena` | AgentGameTestBias | `ad.yFloorConstraint` | this commit (P4b wave 3) | this commit | identical — PASS both loaders ×2 (A2a `YFloor` prunes pit descent) |
| `leashHardArena` | AgentGameTestBias | `ad.leashHard` | this commit (P4b wave 3) | this commit | identical — PASS both loaders ×2 (A2a `LeashHardRadius` inside/outside) |
| `forbidWaterArena` | AgentGameTestBias | `ad.forbidWater` | this commit (P4b wave 3) | this commit | identical — PASS both loaders ×2 (A2b-c `NoWater` prunes wading) |
| `forbidDigArena` | AgentGameTestBias | `ad.forbidDig` | this commit (P4b wave 3) | this commit | identical — PASS both loaders ×2 (A2b-c `NoBreak` prunes DownBreak, allowBreak ON) |
| `shorelineHugArena` | AgentGameTestBias | `ad.shorelineHug` | this commit (P4b wave 3) | this commit | identical — PASS both loaders ×2; `.withChunkRadius(2)` (A3b `ShorelineHug` receding-shore dip) |
| `shorelineSmootherArena` | AgentGameTestBias | `ad.shorelineSmoother` | this commit (P4b wave 3) | this commit | identical — PASS both loaders ×2; `.withChunkRadius(2)` (A3b bias-aware `stringPull` discrimination) |
| `escapeFarthestNoRockDrillArena` | AgentGameTestBias | `ad.escapeFarthestNoRockDrill` | this commit (P4b wave 3) | this commit | identical — PASS both loaders ×2 (gap #59 no downward rock-drill best-effort) |
| `budgetAwayTunnelChurnArena` | AgentGameTestBias | `ad.budgetAwayTunnelChurn` | this commit (P4b wave 3) | this commit | identical — PASS both loaders ×2 (gap #63 no away-tunnel drift best-effort) |

**No helpers orphaned this wave.** The shared `AgentGameTestSupport` helpers the scenes needed
(`maxPathY`) were **inlined** into `AgentDriverBiasScenes` rather than deleted from
`AgentGameTestSupport` — `maxPathY` is still used by surviving Server-family arenas, and
`grantWaterEffects`/`buildFloor`/`clearBox` remain in use by other families. `AgentGameTestBias`
carried no helpers of its own beyond the per-arena private geometry checkers (which moved into
the scene class), so its deletion orphaned nothing.

**Dual-loader determinism (first-run A/B, 2026-07-18).** neoforge dogfood ×2 and fabric
dogfood ×2 (each on the wiped `run-dogfood/world`, servers run sequentially): all four runs
GREEN; the `(name, outcome)` result set is **byte-identical across both runs of each loader AND
across loaders** (38 entries = 2 builtin + 21 existing `ad.*` (9 original + 12 Terrain) + 13 new,
plus canaries `canaryMustFail`→FAIL / `canaryMustTimeout`→TIMEOUT and `canaryMustSwallow`
correctly omitted). The existing 21 `ad.*` scenes stayed PASS (goldens intact). No new scene was
flaky; no threshold was tuned.

**Post-deletion legacy reconcile (2026-07-18).** `scripts/run_gametests.sh` reconciled
`registered=96 entered=96` with **0 swallowed / 0 drifted**, **All 96 required tests passed**,
and a **single optional failure `vineoverwaterclimbarena`** (the −711 live bug, in the permitted
survivor set `⊆ { vineoverwaterclimbarena (optional), deepwatercross*, agentrpcsmoke,
deepwaterclimboutnoblockarena }`). No deleted Bias name reappears (the `forbiddig` substring in
the manifest belongs to the surviving Server-class `serverForbidDigWallArena` /
`forbidDigPadRamArena`, not the retired Bias `forbidDigArena`). No livelock this run.

## Non-closure note — deleting a twin does NOT close its engine task

Retiring a legacy twin is a *test-suite* bookkeeping action, not an engine fix.
Two open engine tasks keep their signatures alive **in the surviving `ad.*`
scenes**, and stay open:

- **task#86** (selfShaftDigUp / gap #53): the `ad.selfShaftDigUp` scene is a
  *required signature gate* — it PASSES only while the walker fails in exactly
  the known way (`reached && worstBackslide > 15.0`, golden
  `20.252203415101263`). Deleting `selfShaftDigUpArena` removes the legacy A/B
  double; the defect gate lives on in the scene. task#86 stays open.
- **task#87** (entityLeashRepathArena low-y): the legacy twin's phase-2 failure
  was adjudicated a master-inherited rig/environment issue at y≈−60 (void-fall
  rig-disease family), tracked as task#87 for later low-y root-cause. Deleting
  the twin removes that particular RED from the legacy suite but does **not**
  close task#87; the `ad.entityLeash` scene (GREEN at grid y=200) carries the
  mechanism-fidelity gate forward.

(Also still open and untouched by this wave: task#88 harness tick-debt within()
fragility.)

## Wave 4 (P4b Task 3) — the Water double-family (WaterBank 11 + WaterCross 10) migrate

**Count arithmetic: legacy registered 96 → 75** (−21 `@GameTest` methods, two whole
classes retired). `AgentGameTestWaterBank` (11) → `AgentDriverWaterBankScenes`;
`AgentGameTestWaterCross` (10) → `AgentDriverWaterCrossScenes`; both classes DELETED and
their two `AgentGameTestRegistrar` `event.register(...)` lines removed in this same commit
(NeoForge `@GameTestHolder` auto-scan also drops them once the classes are gone). The
reconcile (`scripts/gt_reconcile.py`) is fully dynamic (counts the manifest), so no script
constant needed updating.

| deleted legacy test method | legacy class | ad.* scene | migration commit | this deletion | notes (first-run A/B verdict) |
|---|---|---|---|---|---|
| `waterPhysicsParity` | AgentGameTestWaterBank | `ad.waterPhysicsParity` | this commit (P4b wave 4) | this commit | identical — PASS both loaders ×2 (sink/rise/swim water-physics parity; `buildWaterColumn` inlined) |
| `buoyantWallArena` | AgentGameTestWaterBank | `ad.buoyantWall` | this commit (P4b wave 4) | this commit | identical — PASS both loaders ×2 (+5 sheer buoyant wall mount, bobTicks≤120) |
| `vineClingFidelityProbe` | AgentGameTestWaterBank | `ad.vineClingFidelityProbe` | this commit (P4b wave 4) | this commit | legacy `required=false` → `.withRequired(false)`; PASS both loaders ×2 (wall-backed vine cling fidelity) |
| `vineOverWaterClimbArena` | AgentGameTestWaterBank | `ad.vineOverWaterClimb` | this commit (P4b wave 4) | this commit | legacy `required=false` (live −711 bug) → `.withRequired(false)`; **optional-FAIL both loaders ×2 (pocketTicks=29, byte-identical) — the VISIBLE −711 repro, NOT tuned** |
| `tallBankDigClimbArena` | AgentGameTestWaterBank | `ad.tallBankDigClimb` | this commit (P4b wave 4) | this commit | identical — PASS both loaders ×2 (`faithfulBreak` slow stone-mine dig-climb; ~440 ms/scene, well under the 60 s watchdog — the descentDrift open-void-churn hazard does not recur here) |
| `waterLowBankArena` | AgentGameTestWaterBank | `ad.waterLowBank` | this commit (P4b wave 4) | this commit | identical — PASS both loaders ×2 (+2 low-bank foothold-place; `isUsableBuildBlock` sand/gravel/mud guards) |
| `riverSheerBankArena` | AgentGameTestWaterBank | `ad.riverSheerBank` | this commit (P4b wave 4) | this commit | **`.withRequired(false)` — gap #48 shared-body FALSE-GREEN surfaced by isolation (task#91). Deterministic optional-FAIL both loaders ×2 (step=FAILED, wallPressTicks=51, byte-identical). Config byte-identical to legacy (both apply `applyGameTestBaseline()`); the ONLY variable is shared→unique body. NOT tuned.** |
| `deepWaterCrossArena` | AgentGameTestWaterBank | `ad.deepWaterCross` | this commit (P4b wave 4) | this commit | identical — PASS both loaders ×2 (deep 8-block surface swim + Fall/FallIntoWater submerged-bed predicate) |
| `deepWaterClimboutNoBlockArena` | AgentGameTestWaterBank | `ad.deepWaterClimboutNoBlock` | this commit (P4b wave 4) | this commit | gap #48 shared-body lottery member → **deterministic GREEN in the createUnique isolated shell** (PASS both loaders ×2). Shell difference (body isolation removes the flake mechanism), NOT a threshold rebaseline |
| `deepWaterClimboutDriftArena` | AgentGameTestWaterBank | `ad.deepWaterClimboutDrift` | this commit (P4b wave 4) | this commit | identical — PASS both loaders ×2 (drift-entry +2 bank latched dig, ashoreTick≤120) |
| `waterFarAimBankCornerArena` | AgentGameTestWaterBank | `ad.waterFarAimBankCorner` | this commit (P4b wave 4) | this commit | identical — PASS both loaders ×2 (in-water divider round-the-gap smoke; legacy `batch="solo…"` dropped — createUnique isolates) |
| `goalSnapBuriedArena` | AgentGameTestWaterCross | `ad.goalSnapBuried` | this commit (P4b wave 4) | this commit | identical — PASS both loaders ×2 (goal-snap to a standable cell; legacy `floorY=64` kept → mapped `origin.y−136`) |
| `basinArena` | AgentGameTestWaterCross | `ad.basin` | this commit (P4b wave 4) | this commit | identical — PASS both loaders ×2; **`.withChunkRadius(2)`** (plateau dz `0..44` > +31 edge; radius-2 window `[−32,+47]`) (`pathfinderDepthPenalty` anti-basin-dive) |
| `waterClimbOutRouteArena` | AgentGameTestWaterCross | `ad.waterClimbOutRoute` | this commit (P4b wave 4) | this commit | identical — PASS both loaders ×2 (floating-water +1 climb-out structural gate, both A/B legs flush) |
| `waterStepDownFloatArena` | AgentGameTestWaterCross | `ad.waterStepDownFloat` | this commit (P4b wave 4) | this commit | identical — PASS both loaders ×2 (`walkerWaterStepDownFloat` OFF-wedge/ON-advance A/B; `beginReplay`) |
| `forbidDigPadRamArena` | AgentGameTestWaterCross | `ad.forbidDigPadRam` | this commit (P4b wave 4) | this commit | identical — PASS both loaders ×2 (per-goto `forbidDig` lily-pad head-on-break leak; `NoBreak` A/B; `runPadLeg` inlined) |
| `deepWaterSubmergedCrossArena` | AgentGameTestWaterCross | `ad.deepWaterSubmergedCross` | this commit (P4b wave 4) | this commit | identical — PASS both loaders ×2 (`pathfinderFloatingSurfaceCross` — a deterministic planner A/B, NOT subject to the shared-body flake despite the `deepwater*` name) |
| `vineOverWaterCrossArena` | AgentGameTestWaterCross | `ad.vineOverWaterCross` | this commit (P4b wave 4) | this commit | identical — PASS both loaders ×2 (`pathfinderVineOverWaterTax` center-lane detour) |
| `padOverWaterCrossArena` | AgentGameTestWaterCross | `ad.padOverWaterCross` | this commit (P4b wave 4) | this commit | identical — PASS both loaders ×2 (`pathfinderPadOverWaterTax` Y-aware sparse-pad detour) |
| `padClusterCrossArena` | AgentGameTestWaterCross | `ad.padClusterCross` | this commit (P4b wave 4) | this commit | identical — PASS both loaders ×2 (`pathfinderPadClusterTax` 3-region A/B: wall / lone / full-width) |
| `deepWaterFloatBeelineArena` | AgentGameTestWaterCross | `ad.deepWaterFloatBeeline` | this commit (P4b wave 4) | this commit | identical — PASS both loaders ×2; **`.withChunkRadius(4)`** (`spanX=56` → basin/clear box reach dx `+72` > radius-2 `+47` and radius-3 `+63`; radius-4 window `[−64,+79]`) (`Walker.adoptForTest` anchor-gate accept/reject) |

**Lottery / optional governance — three optionals this wave (the phase's heaviest `required`-discipline wave).**
- **`ad.vineOverWaterClimb`** (legacy `required=false`, the live −711 bug): migrated `.withRequired(false)`
  with a javadoc citing the −711 record. It FAILs deterministically (pocketTicks=29, byte-identical
  ×2×2) — the bot detaches off the wall-less vine into the pocket. That optional-FAIL IS the proof it
  reproduces the live bug against the clean (walkerVineFreeHangClimb-OFF) baseline; its RED stays
  VISIBLE, never tuned.
- **`ad.deepWaterClimboutNoBlock`** (gap #48 shared-body lottery member, solo-GREEN proven this phase,
  full-run flaky in the OLD shared-body suite): in the createUnique isolated body the shared-body flake
  mechanism disappears, so it runs **deterministically GREEN** (kept `required=true`). Per the brief this
  is the *expected outcome of body isolation* — a shell difference recorded here, NOT a rebaseline of
  thresholds. The `deepwatercross*` family names (also flagged as candidate lottery members) were
  characterised the same way from observed ×2×2 behaviour: `ad.deepWaterCross` (WaterBank integration,
  GREEN), `ad.deepWaterClimboutDrift` (GREEN), and the pure-planner `ad.deepWaterSubmergedCross` (GREEN)
  are all deterministic — none flaky, none marked optional.
- **`ad.riverSheerBank`** (the gap #48 false-green this wave SURFACED, task#91): it is the one WaterBank
  scene isolation flips legacy-GREEN → deterministic RED. The config is byte-identical to legacy — the
  legacy GameTestServer applies `BotConfig.applyGameTestBaseline()` at boot (zeroing the walker
  water-escape flag family: walkerBankDig*, walkerBuoyantSearchFromSurface,
  walkerSwimAshorePillarDespiteDeepDig, walkerFloatingBankBobFreeze, …), the exact baseline
  `pinnedBaseline()` re-applies. Geometry and start pose are byte-identical; the SOLE differentiator is
  the legacy shared body (concurrent GameTest batches shove/teleport the per-level singleton ashore) vs
  the serial createUnique body — precisely the `descentDriftArena` / `descentOvershootResyncArena`
  false-green mechanism. So the free-drift open-river sheer-bank climb-out genuinely wedges under the
  authored default-OFF baseline (a real executor gap); its legacy green was a shared-body artefact.
  Migrated `.withRequired(false)` with a javadoc + task#91 — RED stays VISIBLE, **NOT tuned** (per the
  wave brief's "flaky/false-green → optional + file, never tune" rule; here deterministic-RED, not flaky).

**Helper decisions (promote/inline only what this wave needs).**
- `AgentGameTestSupport.buildWaterColumn` → inlined private static in `AgentDriverWaterBankScenes`
  (only `ad.waterPhysicsParity` needs it), faithful copy.
- `AgentGameTestSupport.runSearch` + `maxPathY` → inlined private statics in
  `AgentDriverWaterCrossScenes` (used by `ad.basin` / `ad.waterClimbOutRoute`). The sibling wave-3
  `AgentDriverBiasScenes.maxPathY` is PRIVATE (not a promoted shared symbol), so it cannot be reused
  across the source-set — this is the "each provider self-contains its needed helpers" precedent
  (wave-2 inlined `buildFloor`, wave-3 inlined `maxPathY`), NOT a third stray copy of a promoted API.
  `AgentGameTestSupport` keeps all three (still used by surviving Server-family arenas), so nothing was
  orphaned.
- `grantWaterEffects` → `SimProbes.grantWaterEffects` (the common single source), as waves 2-3.
- `ServerPlayerAvatar.faithfulBreak` is a static field NOT covered by `pinnedBaseline()`; `ad.tallBankDigClimb`
  saves/restores it via its own `ctx.cleanup`. Legacy shared-body parking / anti-contamination `finally`
  blocks and `batch="solo…"` isolation batches were DROPPED — a createUnique body cannot bleed into
  another scene.

**Dual-loader determinism (first-run A/B, 2026-07-18).** neoforge dogfood ×2 and fabric dogfood ×2 (each
on the wiped `run-dogfood/world`, servers run one at a time): all four runs GREEN; the `(name, outcome)`
result set is **byte-identical across both runs of each loader AND across loaders**. The two optional-FAILs
(`ad.vineOverWaterClimb` pocketTicks=29; `ad.riverSheerBank` step=FAILED wallPressTicks=51) reproduce to the
exact coordinate on every run and both loaders. The existing 34 `ad.*` scenes (9 original + 12 Terrain + 13
Bias) stayed PASS (goldens intact). No scene was flaky; no threshold was tuned.

**Post-deletion legacy reconcile (2026-07-18).** `scripts/run_gametests.sh` reconciled
`registered=75 entered=75` with **0 swallowed / 0 drifted**, build_success=True, **"All 75 required tests
passed :)"**, and **ZERO failures** (`required_failed=False`; the permitted survivor set after this wave is
just `{ agentrpcsmoke }`, which itself PASSED). No deleted Water name reappears (the count fell exactly
96→75 = −21; the `forbiddig` substring in the manifest now belongs only to the surviving Server-class
`serverForbidDigWallArena`, since the migrated `forbidDigPadRamArena` left with WaterCross). No livelock
this run (9.264 s for all 75). VERDICT: GREEN.

## Wave 5 (P4b Task 4) — the Core (main `AgentGameTest`) + CombatSense + BuildBlock families migrate

**Count arithmetic: legacy registered 75 → 59** (−16 `@GameTest` methods, all migrated twins deleted;
**no** retired-without-scene this wave). Three whole classes retired: `AgentGameTest` (main, 12) →
`AgentDriverCoreScenes`; `AgentGameTestCombatSense` (2) → `AgentDriverCombatScenes`;
`AgentGameTestBuildBlock` (2) → `AgentDriverBuildScenes`. All three self-registered via BOTH
`@GameTestHolder` auto-scan AND an explicit `AgentGameTestRegistrar` `event.register(...)` line; all three
class files were DELETED and all three Registrar lines removed in this same commit — **after this wave the
Registrar registers ONLY `AgentGameTestServer`** (the P4c Server family). The reconcile
(`scripts/gt_reconcile.py`) is fully dynamic (counts the manifest), so no script constant needed updating.
Three provider service lines appended to the common `SceneProvider` file; the 16 `ad.*` names added to BOTH
`expected-scenes-{neoforge,fabric}.txt` in this commit.

**`agentRpcSmoke` — migrated to a scene (count/placement note).** The wave brief noted "agentRpcSmoke itself
is in Server, NOT yours (P4c)". That is reconciled against the explicit provider spec "AgentDriverCoreScenes
(main class 12)" and the hard arithmetic: `AgentGameTestServer` already holds exactly 59 tests, so the target
`75 → 59` is only reachable if ALL 16 non-Server tests leave the legacy suite (keeping/moving `agentRpcSmoke`
into Server would make 60, failing the `registered==59` gate). `agentRpcSmoke` is therefore migrated as
`ad.agentRpcSmoke` — it drives the full JS validation suite (`AgentDriverCommon.runValidation()`) on a worker
thread and polls completion via `SceneContext.await`, the faithful analogue of the legacy
`startSequence().thenWaitUntil`. The dogfood server starts the RPC server unconditionally on a random port, so
the suite's `RpcBridge`/`McpBridge` round-trips have a live endpoint and drain through `server.execute()` on
the ticks the harness advances between polls. It runs GREEN and deterministic on both loaders (145/156 ticks
neoforge, 174 ticks fabric — tick count wobbles with the suite's real-time RPC latency, but the outcome is
invariant, and the determinism gate is on `(name, outcome)`). The deep RPC-parity FAMILY coverage continues
in the Server suite for P4c; this scene is the faithful port of the main-class smoke gate. **Flagged for
controller review** (task-4-report.md §agentRpcSmoke) since it resolves a stated tension in the brief.

| deleted legacy test method | legacy class | ad.* scene | migration commit | this deletion | notes (first-run A/B verdict) |
|---|---|---|---|---|---|
| `agentRpcSmoke` | AgentGameTest | `ad.agentRpcSmoke` | this commit (P4b wave 5) | this commit | identical — PASS both loaders ×2 (full JS validation suite on a worker thread; `await` poll replaces `thenWaitUntil`; RPC/MCP round-trips drain on harness ticks) |
| `pinchArena` | AgentGameTest | `ad.pinch` | this commit (P4b wave 5) | this commit | identical — PASS both loaders ×2 (pure-CPU `PinchArena` vertical-escape budget matrix) |
| `horizonArena` | AgentGameTest | `ad.horizon` | this commit (P4b wave 5) | this commit | identical — PASS both loaders ×2 (pure-CPU `HorizonArena` receding-horizon + soft-commit; `HorizonArenaMinReach` inlined) |
| `inputReleaseGate` | AgentGameTest | `ad.inputReleaseGate` | this commit (P4b wave 5) | this commit | identical — PASS both loaders ×2 (pure-CPU `InputReleaseGate` manual-input-clobber guard) |
| `schemaUnionRendering` | AgentGameTest | `ad.schemaUnionRendering` | this commit (P4b wave 5) | this commit | identical — PASS both loaders ×2 (pure-CPU gap#67-④ `ToolCatalog` union-type render matrix; `assertUnionType` inlined) |
| `physicsParity` | AgentGameTest | `ad.physicsParity` | this commit (P4b wave 5) | this commit | identical — PASS both loaders ×2 (`ServerPlayerAvatar` travel/jump/step-up parity; `create`→`createUnique` ×3) |
| `buildBlockWhitelistArena` | AgentGameTest | `ad.buildBlockWhitelist` | this commit (P4b wave 5) | this commit | identical — PASS both loaders ×2 (`isUsableBuildBlock` + `LevelWorldView.placeableBlockCount` bamboo/sand/whitelist matrix) |
| `pathArchiveJsonArena` | AgentGameTest | `ad.pathArchiveJson` | this commit (P4b wave 5) | this commit | identical — PASS both loaders ×2 (pure-CPU `PathArchive` JSON round-trip + v2 SNBT/NBT) |
| `nodePhysicsArena` | AgentGameTest | `ad.nodePhysics` | this commit (P4b wave 5) | this commit | identical — PASS both loaders ×2 (`NodePhysics.compute` pose-fit/hazard + edit-aware toBreak/toPlace flips; floorY `origin.y−21`) |
| `pathArchiveCaptureArena` | AgentGameTest | `ad.pathArchiveCapture` | this commit (P4b wave 5) | this commit | identical — PASS both loaders ×2 (real Walker goto capture; `Thread.sleep` file-write wait → `await` continuation) |
| `replayRoundTripArena` | AgentGameTest | `ad.replayRoundTrip` | this commit (P4b wave 5) | this commit | identical — PASS both loaders ×2 (record→rebuild→replay round-trip; two sequential file waits → chained `await`s; walker loops stay synchronous) |
| `clientChatLogSemantics` | AgentGameTest | `ad.clientChatLogSemantics` | this commit (P4b wave 5) | this commit | identical — PASS both loaders ×2 (pure-JVM `ClientChatLog.Buffer` seq/since/tail/eviction; loads on dedicated server as the legacy twin did) |
| `threatScanZombieArena` | AgentGameTestCombatSense | `ad.threatScanZombie` | this commit (P4b wave 5) | this commit | identical — PASS both loaders ×2 (Enemy-mob scan control; `makeMockPlayer` reproduced + entity-visibility `await`) |
| `threatScanHurtAttackerArena` | AgentGameTestCombatSense | `ad.threatScanHurtAttacker` | this commit (P4b wave 5) | this commit | identical — PASS both loaders ×2 (gap#55 neutral-attacker `attackedMe` scan; `makeMockPlayer` + entity-visibility `await`) |
| `buildBlockRejectsInteractiveArena` | AgentGameTestBuildBlock | `ad.buildBlockRejectsInteractive` | this commit (P4b wave 5) | this commit | identical — PASS both loaders ×2 (gap#57 interactive-block rejection predicate probe) |
| `valuablePlacementBlockMatrix` | AgentGameTestBuildBlock | `ad.valuablePlacementBlockMatrix` | this commit (P4b wave 5) | this commit | identical — PASS both loaders ×2 (gap#81 valuable/throwaway predicate matrix; dist-neutral `isThrowawaySupportBlock` core, not client-only `BotInteract`) |

**Classification (honest, per deletion source).** The main class was a mix, not one pattern:
- **Pure-CPU / in-memory** (resolve on the first RUN tick, no world): `ad.pinch`, `ad.horizon`,
  `ad.inputReleaseGate`, `ad.schemaUnionRendering`, `ad.pathArchiveJson`, `ad.clientChatLogSemantics`.
- **Level-only** (build blocks + assert, no avatar): `ad.nodePhysics`.
- **Avatar-driven** (`ServerPlayerAvatar.createUnique`): `ad.physicsParity`, `ad.buildBlockWhitelist`,
  `ad.pathArchiveCapture`, `ad.replayRoundTrip`.
- **Worker-thread poll**: `ad.agentRpcSmoke`.
- **CombatSense** are single-tick sensing probes; **BuildBlock** are pure `BotConfig` predicate probes
  (planner/API-only).

**CombatSense — time-handling + entity-visibility findings (per brief).** Both threatScan scenes are
single-tick sensing probes with `setNoAi(true)` + `setPersistenceRequired()` mobs, so the documented
"daytime zombie auto-burn false-signal" hazard CANNOT fire (no ticks elapse for the mob to catch fire before
the scan). Neither `TestkitHarness` nor `BotConfig.applyGameTestBaseline` pins world time, and the dogfood
world is a flat survival world (doDaylightCycle default) — but that is **irrelevant** here because the scenes
are time-independent (verified: byte-identical outcome across both loaders ×2). The legacy rig carried NO
roof/helmet/night protection; the only protection it needed (NoAI + persistence) is copied verbatim, so no
`withRequired(false)` and no citation are warranted. The GameTest-only `helper.makeMockPlayer(GameType)` was
reproduced byte-for-byte as a private helper (a plain **vulnerable** `Player` — a `ServerPlayerAvatar`
FakePlayer is `isInvulnerableTo`→true and would defeat the `hurt()` attacker test). **One environmental
adaptation, faithful, not a semantic change:** on a real dedicated server a freshly force-loaded arena chunk
is not yet ENTITY_TICKING when the scene body runs, so a mob added by `addFreshEntity` is alive in the level
but not yet in the queryable section index (measured: `getEntitiesOfClass`→0 with the mob alive at its exact
coords; visible ~tick 9). `ThreatScanner` scans via `Level.getEntities`, so the scene polls (bounded, `await`,
no manual `level.tick()`) until the mob is indexed, then runs the byte-identical scan+assert. The legacy
GameTestServer placed its arena in an already-ticking template, masking this.

**Origin slots.** All 16 take AUTO slots at the default radius — every arena's footprint fits the default
window `dx/dz ∈ [−16,+31]` (widest are `ad.pathArchiveCapture`/`ad.replayRoundTrip` at dx +20), and every gate
is a discrete OUTCOME / pure-CPU assertion / wide-tolerance metric (no byte-determinism pin), so registry-
growth relocation cannot flip them. No `withChunkRadius`, no `withOriginSlot`.

**Helper decisions (promote/inline only what this wave needs).** `AgentGameTestSupport.buildFloor` → inlined
private static in each of `AgentDriverCoreScenes` and `AgentDriverCombatScenes` (faithful copy; not imported
across the neoforge testmod boundary). `HorizonArenaMinReach` was used ONLY by the main class `horizonArena`;
it is now orphaned, so it was DELETED from `AgentGameTestSupport` (inlined into `ad.horizon` as
`HorizonArena.CORRIDOR_LEN − 20`). `AgentGameTestSupport` otherwise stays intact — `buildFloor`, `clearBox`,
`grantWaterEffects`, `gtOnlySkips`/`gtSkip` remain in use by the surviving Server family. `assertUnionType`
(was a private static in the main class) moved into `AgentDriverCoreScenes`, adapted to `SceneContext.fail`.
`grantWaterEffects` was not needed by any wave-5 scene. The GameTest-only `makeMockPlayer` was reproduced in
`AgentDriverCombatScenes` (vanilla `GameTestHelper.makeMockPlayer` has no scene-side equivalent).

**Dual-loader determinism (first-run A/B, 2026-07-18).** neoforge dogfood ×2 and fabric dogfood ×2 (each on a
wiped `run-dogfood/world`, servers run one at a time): all four runs GREEN; the `(name, outcome)` result set
is **byte-identical across both runs of each loader AND across loaders** (75 scene records = 2 builtin + 2
canaries recorded (`canaryMustFail`→FAIL / `canaryMustTimeout`→TIMEOUT, `canaryMustSwallow` correctly omitted)
+ 71 `ad.*`). All 16 new scenes PASS on both loaders ×2. The pre-existing 55 `ad.*` scenes were unchanged
(the two expected optional-FAIL sensors `ad.vineOverWaterClimb` pocketTicks=29 and `ad.riverSheerBank`
wallPressTicks=51 reproduced to the coordinate; `ad.vineClingFidelityProbe` stayed optional-PASS). No new
scene was flaky; no threshold was tuned.

**Post-deletion legacy reconcile (2026-07-18).** `scripts/run_gametests.sh` reconciled
`registered=59 entered=59` with **0 swallowed / 0 drifted**, build_success=True, **All 59 required tests
passed**, VERDICT GREEN — the legacy suite is now Server-only and its surviving-failure family is empty
(`agentrpcsmoke` migrated out this wave). No deleted name reappeared; no livelock this run.

## P4b closing summary (Task 5) — non-Server families fully migrated

**The whole-phase count chain, audited against git history.** P4b migrated every
non-Server legacy `@GameTest` family off the legacy suite in four bounded
migrate-then-delete waves, each wave a single feat/chore commit whose subject line
carries its own arithmetic. Reading the chain end to end:

| wave | family | migrated → scenes | retired-without-scene | legacy count | commit(s) |
|---|---|---|---|---|---|
| — (P4a wave 1) | 8 already-migrated twins | 8 | 0 | 130 → 122 | `1f30322` |
| P4b wave 2 | Terrain (`AgentGameTestTerrain`) | 12 | 1 (`descentDrift`) | 122 → 110 → **109** | `3eb6d62` + `159f202` |
| P4b wave 3 | Bias (`AgentGameTestBias`) | 13 | 0 | 109 → **96** | `1d2f748` |
| P4b wave 4 | WaterBank (11) + WaterCross (10) | 21 | 0 | 96 → **75** | `84ffea5` |
| P4b wave 5 | Core (12) + CombatSense (2) + BuildBlock (2) | 16 | 0 | 75 → **59** | `3fc846f` |

**Chain: 122 → 109 → 96 → 75 → 59.** Every arrow above matches the `Count
arithmetic` line of its wave section and the `feat/chore(testkit): … (X→Y)` subject
of its commit. The four P4b waves removed **63** legacy `@GameTest` methods total
(**62 migrated** to `ad.*` scenes + **1 retired-without-scene**, `descentDriftArena`,
controller-adjudicated in wave 2), taking the legacy suite from 122 to 59.

**Legacy suite is now Server-only.** After wave 5 the `AgentGameTestRegistrar`
registers **only** `AgentGameTestServer` — the surviving 59 tests are exactly the
Server family, the P4c cut. `AgentGameTestTerrain`, `AgentGameTestBias`,
`AgentGameTestWaterBank`, `AgentGameTestWaterCross`, `AgentGameTest` (main),
`AgentGameTestCombatSense`, and `AgentGameTestBuildBlock` are all DELETED;
`AgentGameTestSupport` survives (its `buildFloor`/`clearBox`/`grantWaterEffects`/
`gtOnlySkips` helpers are still used by the Server family).

**Dogfood suite is now 71 `ad.*` scenes** across eight providers (all in
`common/src/testmod/.../scene/`, one common `SceneProvider` service file, both loaders):
`AgentDriverScenes` (9 original) + `AgentDriverTerrainScenes` (12) +
`AgentDriverBiasScenes` (13) + `AgentDriverWaterBankScenes` (11) +
`AgentDriverWaterCrossScenes` (10) + `AgentDriverCoreScenes` (12) +
`AgentDriverCombatScenes` (2) + `AgentDriverBuildScenes` (2) = **71**
(9 + 62 migrated). The plan estimated 72; the actual is 71 because `descentDrift`
retired without a scene.

**Three deliberate optional-FAIL sensors** carry live-bug / false-green signatures
forward as VISIBLE, never-tuned gates (each `.withRequired(false)` with a task
citation in its scene javadoc):

- `ad.vineClingFidelityProbe` — legacy `required=false`; optional-**PASS** (wall-backed
  vine cling fidelity; kept optional to match legacy).
- `ad.vineOverWaterClimb` — the live **−711** bug (`walkerVineFreeHangClimb`-OFF baseline);
  optional-**FAIL** deterministically (pocketTicks=29). Its RED IS the repro proof.
- `ad.riverSheerBank` — **task#91**: a gap #48 shared-body FALSE-GREEN that isolation
  (shared→`createUnique` body) flipped to a deterministic RED (step=FAILED,
  wallPressTicks=51). Config byte-identical to legacy; the only variable is the body.
  optional-**FAIL**, NOT tuned.

**Residuals (open, unchanged by P4b).** The surviving legacy family is **P4c** —
`AgentGameTestServer`'s 59 tests, which include the four re-entrant manual
`level.tick()` sites (notably the livelock-prone `serverForbidDigWallArena` /
`serverCombat*` arenas) that are the migration mines. **P4-final** then retires the
`GameTestServer` + `solo*` batch mechanism and switches `run_gametests.sh`. Open
engine/harness tasks tracked in the surviving scenes stay open: **task#86**
(`ad.selfShaftDigUp` required signature gate), **task#87** (`entityLeash` low-y rig),
**task#88** (`ad.entityLeash` harness tick-debt), **task#90** (instrument-face dual
verb), **task#91** (`ad.riverSheerBank`). `agentRpcSmoke` migrated to `ad.agentRpcSmoke`
in wave 5 (count-forced: `AgentGameTestServer` already holds exactly 59, so all 16
non-Server tests had to leave to reach `registered==59`) — flagged as a required
watch-item. **Task-5 acceptance found its 259-check JS validation suite is not
portable to the integrated-client (T1/T2) topology** (8 client-face checks diverge —
`observe…player` / `Agent.bot.tunnel` / `blocks_to_avoid` JS bindings + a couple of
behavioural checks — while all 259 PASS on the dedicated path). Per controller
adjudication the scene keeps REQUIRED dedicated-server coverage and carries a **visible
topology guard** (`!isDedicatedServer()` → early PASS with a `SceneContext.passNote`
marker in the results-JSONL reason, citing **task#92**); the real fix (topology-aware
checks / a signature gate pinning the 8 known divergences) is **task#92**. The
`agent_driver-testkit-*` artifactId naming residual is unchanged.

## Wave 6 (P4c Task 1) — the Station family: craft / smelt / recipe / observe, 15 migrated + deleted

**Count arithmetic: legacy registered 59 → 44** (−15 `@GameTest` methods, all migrated twins
deleted; **no** retired-without-scene this wave). This is the FIRST P4c Server-family wave: unlike
P4b (whole-class families) the `AgentGameTestServer` giant is cut by THEME, so this wave migrates a
15-name Station subset to a new `AgentDriverStationScenes` provider and deletes the twins IN PLACE —
`AgentGameTestServer` survives with its remaining 44 tests and `AgentGameTestRegistrar` is untouched
(still registers `Server`). The reconcile (`scripts/gt_reconcile.py`) is fully dynamic (counts the
manifest), so no script constant needed updating. One provider service line appended to the common
`SceneProvider` file; the 15 `ad.*` names added to BOTH `expected-scenes-{neoforge,fabric}.txt` in
this commit.

**Driver / body substitution.** The Station tests drive real `CraftProcess` / `SmeltProcess`
instances headless over a FakePlayer via `ServerAgentDriver.create` + `ServerAgentManager`
register/tickAll. The canonical `create → createUnique` substitution maps to
`ServerAgentDriver.create → ServerAgentDriver.createIsolated` (which delegates to
`ServerPlayerAvatar.createUnique`, the #48 per-scene isolated body); the common
`ServerAgentDriver.fakePlayer()` returns a plain `ServerPlayer` (a FakePlayer IS a ServerPlayer),
and every station call used — `getInventory()`, `containerMenu`, `inventoryMenu`, `offhand`,
`setItemSlot` — is a `ServerPlayer` member, so the port is type-faithful on both loaders (fabric has
no `ServerAgentDriver`/`ServerAgentManager` shim — it uses the common ones directly). These are
process-outcome / block-state / resolver gates, not `Walker`-flakiness lotteries, so body isolation
does not change any verdict (confirmed byte-identical ×2×2).

**Rig cleanup (#40 persistent-world lesson).** The dogfood world PERSISTS across the ×2 runs, so
every stone floor, furnace, crafting table and avatar a scene spawns is scrubbed in `ctx.cleanup`
(LIFO, all-exit drain). Several legacy bodies wiped only a subset in their `finally` (relying on
far-apart absolute coords in the sprawling GameTest world — e.g. `smeltFuelPolicyArena` cleared only
the furnace, not its 5×5 floor); the ported scenes clear their WHOLE footprint. Verified clean: the
×2 result sets are byte-identical, so no residue leaks between runs.

**Furnace / grid state-machine tests (#64 lesson) — translated faithfully, not softened.**
`ad.smeltFuelPolicy` hands the furnace menu to `fp.containerMenu` directly and injects the cooked
result (the GameTest chunk never ticks the furnace); `ad.serverCraftFailGridReturn` stuffs
`InventoryMenu.getCraftSlots()` and sets `containerMenu` to a `DummyMenu` (NOT `inventoryMenu`, so
CraftProcess's trailing `closeContainer()` lands on a no-op and cannot mask the :133 grid-clear).
Both workarounds were already encoded in the legacy bodies (a synchronous FakePlayer cannot produce
a genuine "placed but never resulted" grid straddle, and `ServerPlayer.doCloseContainer` would
fake-green the grid tests) and are carried over verbatim. The two GameTest-only nested helpers
`CraftLogCatcher` (log4j2 in-memory appender for `ad.serverCraftFailTelemetry`) and `DummyMenu` were
inlined into the provider (private static nested types — not reached across the testmod source-set
boundary), the wave-2/3 "each provider self-contains its needed helpers" precedent.

**Config pinning.** The 11 scenes whose legacy body saved/restored `BotConfig` (all touch
`walkerDebug`; `ad.serverCraftTableReclaim` also `craftReclaimTable=true`,
`ad.serverCraftTableHoleRim` also `craftReclaimTable=false`) use `BotConfig.pinnedBaseline()` +
`ctx.cleanup(pin::close)`. The 4 that touched no config are ported without a pin — 3 are pure
`RecipeResolver` tests (`ad.serverRecipeSpecies`, `ad.serverCraftTableInject`,
`ad.serverRecipeShortfallSpecies`, no world/avatar at all) and 2 grid tests
(`ad.smeltFuelPolicy`, `ad.serverCraftGridClearHelper`) that mutate no config; safe because every
config-mutating scene restores baseline on exit, so non-pinning scenes see clean defaults.

**Origin slots / footprints.** All 15 take AUTO slots at the default `chunkRadius=1` window
(`dx/dz ∈ [−16,+31]`). The only two-sub-rig scene is `ad.serverCraftTableReclaim`: its second,
independent sub-rig (a borrowed pre-existing table) is relocated from the legacy +40/+40 diagonal to
a compact **+16 X offset** so both sub-rigs fit one origin window — each sub-rig's INTERNAL geometry
is byte-unchanged, and both gates are position-invariant process OUTCOMEs (placed table reclaimed /
borrowed table spared), so the relocation cannot flip them. No `withChunkRadius`, no pinned slot. ⛔
No Station scene calls `level.tick()` (the family has none — the re-entrant `level.tick()` mines are
all in the Process wave, P4c Task 4).

| deleted legacy test method | legacy class | ad.* scene | migration commit | this deletion | notes (first-run A/B verdict) |
|---|---|---|---|---|---|
| `serverCraftArena` | AgentGameTestServer | `ad.serverCraft` | this commit (P4c wave 6) | this commit | identical — PASS both loaders ×2 (real CraftProcess 2×2 inventory-grid, ≥4 planks + finish) |
| `serverRecipeSpeciesArena` | AgentGameTestServer | `ad.serverRecipeSpecies` | this commit (P4c wave 6) | this commit | identical — PASS both loaders ×2 (pure RecipeResolver species-follows-presence, gap #274) |
| `serverCraftTableInjectArena` | AgentGameTestServer | `ad.serverCraftTableInject` | this commit (P4c wave 6) | this commit | identical — PASS both loaders ×2 (crafting_table injection dep-first/dedup/suppression, gap #275) |
| `serverRecipeShortfallSpeciesArena` | AgentGameTestServer | `ad.serverRecipeShortfallSpecies` | this commit (P4c wave 6) | this commit | identical — PASS both loaders ×2 (gap #67-①② committed-species snapshot + raw-leaf route) |
| `serverCraftTableReclaimArena` | AgentGameTestServer | `ad.serverCraftTableReclaim` | this commit (P4c wave 6) | this commit | identical — PASS both loaders ×2 (gap #276 placed-table reclaimed / borrowed-table spared; sub-rig B relocated +40/+40 → +16 X, internal geometry unchanged) |
| `serverObservePlayerInventoryArena` | AgentGameTestServer | `ad.serverObservePlayerInventory` | this commit (P4c wave 6) | this commit | identical — PASS both loaders ×2 (gap #41 full 36-slot snapshot + #42 tool wear + namespaced `items` as `have`) |
| `serverPlanHaveDefaultsToBagArena` | AgentGameTestServer | `ad.serverPlanHaveDefaultsToBag` | this commit (P4c wave 6) | this commit | identical — PASS both loaders ×2 (gap #44 omitted `have`→bag / explicit `{}`→hypothesis / verbatim / null-safe) |
| `serverSmeltCliffArena` | AgentGameTestServer | `ad.serverSmeltCliff` | this commit (P4c wave 6) | this commit | identical — PASS both loaders ×2 (SmeltProcess capability-cliff graceful degrade, "熔炉" error) |
| `serverCraftTableHoleRimArena` | AgentGameTestServer | `ad.serverCraftTableHoleRim` | this commit (P4c wave 6) | this commit | identical — PASS both loaders ×2 (gap#61 placeTable searches hole rim dy=+1; craftReclaimTable pinned OFF) |
| `serverSmeltFurnaceHoleRimArena` | AgentGameTestServer | `ad.serverSmeltFurnaceHoleRim` | this commit (P4c wave 6) | this commit | identical — PASS both loaders ×2 (gap#62 placeFurnace shares placeTable's candidate scan) |
| `smeltFuelPolicyArena` | AgentGameTestServer | `ad.smeltFuelPolicy` | this commit (P4c wave 6) | this commit | identical — PASS both loaders ×2 (gap#64 ①coal-not-table / ②reload / ③take back all 3 slots; #64 manual containerMenu + injected result) |
| `serverCraftGridClearHelperArena` | AgentGameTestServer | `ad.serverCraftGridClearHelper` | this commit (P4c wave 6) | this commit | identical — PASS both loaders ×2 (gap#67-③ clearInventoryCraftGrid returns stranded 2×2-grid material; getCraftSlots stuffing) |
| `serverCraftGridConservationArena` | AgentGameTestServer | `ad.serverCraftGridConservation` | this commit (P4c wave 6) | this commit | identical — PASS both loaders ×2 (gap#67-③ happy-path 2×2 conservation: log −1, planks +4, grid empty) |
| `serverCraftFailTelemetryArena` | AgentGameTestServer | `ad.serverCraftFailTelemetry` | this commit (P4c wave 6) | this commit | identical — PASS both loaders ×2 (gap#67-⑥ `[craft] plan` + fail-path log lines; inlined CraftLogCatcher log4j2 appender) |
| `serverCraftFailGridReturnArena` | AgentGameTestServer | `ad.serverCraftFailGridReturn` | this commit (P4c wave 6) | this commit | identical — PASS both loaders ×2 (gap#67-③ FAIL-exit :133 grid-return; DummyMenu unmasks the trailing closeContainer) |

**Orphaned helpers deleted in the same commit (no registered-count effect).** `CraftLogCatcher`
(private nested log4j2 appender, sole caller `serverCraftFailTelemetryArena`) and `DummyMenu`
(private nested menu stand-in, sole caller `serverCraftFailGridReturnArena`) were deleted from
`AgentGameTestServer` (both reproduced inside `AgentDriverStationScenes`). `countItem` (private
static) was deleted — its only callers were the four grid/smelt tests migrated this wave (verified
by grep: zero remaining references). `clearBox` was **kept** — still used by surviving Server-family
arenas (`serverBunker*`, etc.). Eight now-orphaned imports were removed from `AgentGameTestServer`
(`CraftProcess`, `RecipeManager`, `HolderLookup`, `Params`, `RecipeApi`, `RecipeResolver`,
`AbstractContainerMenu`, `InventoryMenu`); `AgentApi` was kept (still used by a surviving arena). One
dangling `{@link #serverObservePlayerInventoryArena}` in the surviving `serverObserveAirSupplyArena`
javadoc (gap#70) was repointed to plain text "the migrated `ad.serverObservePlayerInventory` scene".

**Dual-loader determinism (first-run A/B, 2026-07-18).** neoforge dogfood ×2 and fabric dogfood ×2
(each on a freshly wiped `run-dogfood/world`, servers run one at a time): all four runs GREEN; the
`(name, outcome)` result set is **byte-identical across both runs of each loader AND across loaders**
(90 scene records = 2 builtin + 2 canaries recorded (`canaryMustFail`→FAIL / `canaryMustTimeout`→
TIMEOUT, `canaryMustSwallow` correctly omitted) + 86 `ad.*` (71 existing + 15 new)). All 15 new
scenes PASS on both loaders ×2. The pre-existing 71 `ad.*` scenes were unchanged (the two expected
optional-FAIL sensors `ad.vineOverWaterClimb` and `ad.riverSheerBank` reproduced; the optional-PASS
`ad.vineClingFidelityProbe` stayed PASS). Suite wall-clock: neoforge 70 s / 64 s, fabric 68 s / 63 s
(86-scene growth well within budget). No new scene was flaky; no threshold was tuned.

**Post-deletion legacy reconcile (2026-07-18).** `scripts/run_gametests.sh` (fresh `run-gametest`
world) reconciled `registered=44 entered=44` with **0 swallowed / 0 drifted**, build_success=True,
**"All 44 required tests passed :)"**, `required_failed=False`, VERDICT GREEN. The surviving Server
failure family is empty (unchanged since P4b wave 5), so the empty failure set held. No deleted
Station name reappears anywhere in the manifest (the count fell exactly 59→44 = −15). No livelock
this run.
