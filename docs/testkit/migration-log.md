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

## Wave 2 (P4b Task 1) — the Terrain family: 12 migrated + deleted, 1 retired-without-scene

**Count arithmetic: legacy registered 122 → 110** (−12 `@GameTest` methods). This is
the FIRST P4b family wave and defines the wave protocol Tasks 2–4 replicate: per-name
direct translation + A/B → dual-loader ×2 dogfood result-set identity → same-commit
twin deletion + this log row → legacy reconcile. The reconcile (`gt_reconcile.py`)
is fully dynamic (counts the manifest), so no script constant needed updating.

**Deviation from the plan's 122→109: this wave lands at 110, not 109.** One of the
13 Terrain tests, `descentDriftArena`, is **retired-without-scene, pending controller
adjudication** (P4b escape hatch) — it is NOT migrated and its legacy twin is NOT
deleted. Rationale (full evidence in `.superpowers/sdd/task-1-report.md`):

- `descentDriftArena` is a legacy `required = false` **PROVEN FALSE GREEN** (gap #49):
  it "passed" the shared-body suite only because a concurrent arena shoved the shared
  FakePlayer out of the wedge; SOLO it is deterministically RED (the fix-ON leg still
  LAUNCHES off the stair into open void, minY≈−60). Its own javadoc records that "the
  fix's gate is the LIVE A/B" — the arena is superseded and carries no reliable
  regression signal.
- It is **unmigratable as a faithful synchronous-body scene**: the RED path is an
  open-void A* churn needing ~110 s of compute, and the dogfood harness runs a scene
  body in ONE server tick, so it blows the 60 s `ServerHangWatchdog` and CRASHES the
  whole suite (first-run evidence: `neoforge/run-dogfood/crash-reports/crash-2026-07-18_12.58.53-server.txt`,
  stack rooted at `AgentDriverTerrainScenes.descentDrift → Walker.tick → PathFinder.advance`).
  Its determinism-critical config (`pathfinderMaxNodes` node budget, `sliceMs/maxMs=MAX`)
  cannot be trimmed to fit without rebaselining the search of an already-broken rig, and
  a wall-clock cap would be non-deterministic (breaking the ×2 result-set identity gate).

So this wave migrates **12 of 13** and holds `descentDriftArena` in
`AgentGameTestTerrain` for the controller to adjudicate (accept full retirement +
delete the twin → 109, or require a bounded migration).

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

**Retired-without-scene (NOT deleted; controller adjudication):**

| legacy test method | legacy class | status | rationale |
|---|---|---|---|
| `descentDriftArena` | AgentGameTestTerrain | **KEPT — retired-without-scene candidate** | superseded PROVEN FALSE GREEN (gap #49); unmigratable as synchronous body (>60 s single-tick open-void A* churn → `ServerHangWatchdog` crash); see report §descentDrift |

**No helpers orphaned by name-collision this wave** (unlike wave 1): the shared
`AgentGameTestSupport` helpers the migrated scenes needed (`buildFloor`,
`grantWaterEffects`) are still used by the surviving `descentDriftArena` and other
legacy families, so nothing was deleted from `AgentGameTestSupport`. `buildFloor` was
**inlined** into `AgentDriverTerrainScenes` (origin-relative, promoted-into-class, not
imported across the neoforge testmod source-set boundary); `grantWaterEffects` was
reused from the common `SimProbes` single source.

**Dual-loader determinism (first-run A/B, 2026-07-18).** neoforge dogfood ×2 and fabric
dogfood ×2 (each on a freshly wiped `run-dogfood/world`, servers run sequentially): all
four runs GREEN; the `(name, outcome)` result set is **byte-identical across both runs of
each loader AND across loaders** (23 PASS = 2 builtin + 9 existing `ad.*` + 12 new; plus
the two expected canaries `canaryMustFail`→FAIL / `canaryMustTimeout`→TIMEOUT, and
`canaryMustSwallow` correctly omitted). The existing 9 `ad.*` goldens stayed PASS (golden
bytes intact). No new scene was flaky; no threshold was tuned.

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
