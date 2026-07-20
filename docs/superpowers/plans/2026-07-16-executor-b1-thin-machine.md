# Executor B1 — Thin Ascent Machine Woven Into the Legacy Drive (task#82)

> Supersedes `2026-07-15-executor-travel-actuator-extract-A.md` (Option A hard-extract is DISPROVEN
> by code evidence) and the Units 2-6 of `2026-07-15-executor-permove-statemachine-ascend.md`.
> Unit 1 (scaffolding, commit 05afb27) STANDS — only the delegation branch semantics change.
> User adjudication 2026-07-16: **B1 now (closes task#82) + A″ in-place phase refactor scheduled
> separately as task#84** (not on this critical path).

## Why A hard-extract is dead (evidence)

The travel-actuation tail (Walker.tickInner 4304→5279, ~975 lines) reads ~40 tick-locals produced
by the FRONT half. The core one, `aimYaw` (born line 3938), is the output of the whole aim pipeline
(3283→3938) which hangs on CROSS-TICK Walker fields: `smoothTargetYaw` (line 230, EMA low-pass,
NaN-resync) and `lastAimYaw`/spinFreeze/water-coupling. An external `TravelActuator` would need a
faithful supply of all of it → extract the aim pipeline too (~1700 lines + field lifecycles) → the
"machine" collapses into a thin wrapper around nearly all of tickInner. Per-move ownership would be
fictional, risk enormous, task#82 delivery zero-accelerated.

## B1 architecture

The drive NEVER moves — staying in place, it IS the single source (no re-implementation possible).
The machine becomes a pure decision-weave layer at the existing delegation point (Walker ~4292):

- `PREP/RUNNING/SUCCESS` **fall through** to the legacy jump-timing + drive (no early return —
  the early `return Step.WALKING` at old line 4300 was the flaw that forced the Unit-2
  re-implementation).
- Machine owns: **per-edge episode lifecycle** (episode = consecutive ticks on the same
  from→to/move edge) + **dig-aware vertical-progress watchdog**: bounded episodeTicks with
  neither dy gain nor dig activity → `UNREACHABLE` → `forceFellOffPath` → existing re-route /
  futile-backoff path. That IS the task#82 dead-zone fix.
- **Step advancement stays legacy** (the advance loop at Walker 2265-2288 runs in the front half;
  machine advancing too would race it). Machine SUCCESS is telemetry-only.
- ⚠️ dig-aware: bare-hand stone is 150t+/block (#66 lesson). Progress = dy gain OR active dig
  (breakHold / block-break progress). Only when BOTH are absent does dead-zone time accrue.

A/B surface: flag OFF = byte-identical (Unit 1 proven). Flag ON vs OFF differs in exactly ONE
behavior — the timeout→UNREACHABLE conversion. Live/arena verification watches only that.

## Global constraints (unchanged from the A plan — verbatim discipline)

- FULL suite gate per slice (`rm -rf neoforge/run-gametest/world; ./gradlew
  :neoforge:runGameTestServer`), failure-set-BY-NAME = known names only
  (`entityLeashRepath`, `serverAvatarGearScopeProbe` + optional lottery), ZERO NEW NAMES.
- ⛔ NEVER verify via AGENT_GT_ONLY subset (selfShaftDigUp is solo-RED suite-GREEN lottery).
- ⛔ no pkill — kill hung gametest JVMs by explicit PID; then delete run-gametest/world.
- No new @GameTest files — fold arenas into AgentGameTestTerrain.
- Work IN-SESSION (no background implementer subagents on the shared tree).
- Branch `feature/executor-permove-ascend`, commit per slice, FF-merge pre-authorized.

## Slices

- [x] **B1-1 (6fbc4cd) — fall-through + episode tracking (behavior no-op):** reshape the delegation branch
  (drop the early return; only UNREACHABLE/FAILED act, via forceFellOffPath). AscendMovement becomes
  a real episode tracker (edge identity, episodeTicks, best dy, dig-activity latch) but the watchdog
  is UNARMED (never returns UNREACHABLE). Flag ON is still a behavior no-op → suite must be green
  under both OFF (default) and the noopArena contract. Gate: build + full suite. Commit.
- [x] **B1-2 (a3f9c13; seam-arena verified via explicit AGENT_GT_ONLY execution — see the suite-integrity P0: full runs silently never execute the ascend arenas) — arm the watchdog + task#82 arena (RED→GREEN):** bounded no-progress episode →
  UNREACHABLE. New buried-bot rig in AgentGameTestTerrain (deep-stone shaft, bare-hand, assert the
  churn converts to a re-route/futile terminal instead of infinite dead-zone). **CLOSES task#82.**
  Gate: new arena RED on legacy semantics (flag OFF or watchdog off) → GREEN armed; full suite.
- [x] **B1-3 (2026-07-20) — replay A/B + default-ON flip:** 2×2 A/B over the ascent-heavy corpus
  subset (steep-822/steep-878/crest-815/rev-897; both legs run fresh the same day, screen-watch on,
  current default stack, only `walkerAscendMovement` toggled). Verdict GREEN: the only arrival in
  16 case-runs was an ON leg (B2 steep-822, also its lowest peak stuck); best progress on crest-815
  and rev-897 were ON legs; 10 watchdog UNREACHABLE fires all landed on genuine dead-zone
  stepUp/diagUp poses (a 3-fire re-route loop broke via the existing blacklist backbone in ~15s;
  zero false trips on progressing climbs); every ON churn pocket was a pre-existing OFF-family
  (water-bank/dig/wall-corner — machine-exempt domains); maxStuck sum deltas sat inside the
  identical-flag chaos envelope (per-archive ×4-5 swings between same-flag runs; peak-stuck is
  terminal-behavior-confounded — an early futile FAIL idles the bot and reads as a low score).
  Flipped `walkerAscendMovement` default → ON (baseline-EXEMPT per the lateralGate precedent:
  arenas run the live truth; the noop scene pins OFF explicitly). The 06-28 corpus baseline was
  decoupled from the flip by running both A/B legs fresh; `baseline-flags.json` gained
  `walkerAscendMovement:false` so the archived 06-28 matrix stays reproducible (full task#83
  re-baseline remains separate debt).

## What happens to the old plans

- extract-A plan: Slice 0 characterization notes remain useful for task#84 (A″ in-place phase
  refactor: gather locals into a TravelState struct, split tickInner into private phase methods
  called in unchanged sequence — readability only, byte-identical, separate multi-week effort).
- spec `docs/superpowers/specs/` §"machine owns actuation": adjudicated FALSE for this executor —
  per-move ownership of DISCRETE decisions + lifecycle is right; continuous actuation is a shared
  organ. Update the spec wording in B1-2's commit.
