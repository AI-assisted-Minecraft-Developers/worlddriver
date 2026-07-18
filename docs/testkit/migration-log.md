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
