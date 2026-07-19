# Executor Travel-Actuator Extraction (Option A) — SUPERSEDED 2026-07-16

> ⛔ **SUPERSEDED by `2026-07-16-executor-b1-thin-machine.md`.** Slice 0 characterization found the
> tail's key input `aimYaw` depends on cross-tick Walker fields (`smoothTargetYaw` line 230) fed by
> the whole front-half aim pipeline (3283→3938) — external extraction collapses the machine into a
> wrapper of nearly all of tickInner. User adjudicated: B1 thin machine now; in-place phase refactor
> (A″) scheduled separately as task#84. The Slice 0 notes below remain useful for task#84.

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development or executing-plans.
> Supersedes the Units-2..6 of `2026-07-15-executor-permove-statemachine-ascend.md` for path A.
> Unit 1 (scaffolding, commit 05afb27) STANDS. This plan replaces the "migrate stepUp" units.

**Goal:** Make the per-move ascent machine (AscendMovement) drive `stepUp`/`stairUpBreak`/`diagUp`
through the SAME code the legacy executor uses — by extracting Walker.tickInner's travel-actuation
tail into ONE reusable unit both call. No re-implementation (kills the #45/#46/#47 anti-pattern the
first Unit-2 implementer fell into). Byte-identical throughout.

**Why this plan exists:** The approved spec assumed `stepUp` was a cleanly-separable per-move unit.
Ground truth (2026-07-15, read of Walker.java): `stepUp`'s actuation is NOT separable — the travel
drive (aim / lane-keep / strafe / sprint / tangent / sneak-brake / dive / lava) is **Walker.tickInner
lines 4304→5279 (~975 lines, ~15+ flags), SHARED across flat-walk / diagUp / diagDown / descend /
fall / stepDown / water / bank / parkour / disk-closure**. Only the discrete jump gate + episode
lifecycle is stepUp-specific. So "single source" (Option A) = extract that whole tail.

## Global Constraints (copied verbatim — every task includes these)

- **无作弊 Normal survival run** is the live proving ground; engine capability is the deliverable.
- **BYTE-IDENTICAL at every commit.** Each extraction slice is a pure refactor: zero behavior change.
  The gate is the FULL gametest suite (`rm -rf neoforge/run-gametest/world; ./gradlew
  :neoforge:runGameTestServer`) with the failure-set-BY-NAME containing ONLY known names
  (`entityLeashRepath`, `serverAvatarGearScopeProbe` required + `vineoverwater` optional) and **ZERO
  NEW NAMES**. "A regression only ADDS a new failing name; same known names = shared-state contention."
- ⛔ **NEVER verify via `AGENT_GT_ONLY` subset** — ascent/dig arenas are #48 shared-state lottery
  (selfShaftDigUp is solo/subset-RED but suite-GREEN). Subset = false RED. Full suite only.
- ⛔ **禁止 pkill** — kill lingering gametest JVMs by explicit PID. If a full-suite run hangs
  (underwaterBase), kill by PID, `rm -rf neoforge/run-gametest/world`, re-run (fresh world clears it).
- **No new @GameTest file** — fold new arenas into the existing AgentGameTestTerrain/AgentGameTestServer.
- **Feature branch** `feature/executor-permove-ascend`; FF-merge is pre-authorized; commit per slice.
- Reference line numbers are as of commit 05afb27 and DRIFT after each slice — always grep current.

## Architecture

`Walker.tickInner` (645→5279) keeps its front half (planning, recovery gates, actuator selection).
Its travel-actuation tail (4304→5279) becomes `TravelActuator.drive(TravelState st)` where `TravelState`
is a bundle of the tail's ~40 read-locals + a back-reference to the Walker fields it mutates
(stepUpBackoff*, descentDriveRejectStreak, climbPressConsec, steepDescentLatch, …). Legacy tickInner
builds `TravelState` from its locals and calls `TravelActuator.drive(st)`. AscendMovement, when it owns
an edge, builds the same `TravelState` (from MovementContext + a re-derivation of the few locals it
lacks) and calls the SAME `TravelActuator.drive(st)` — so the drive is literally one source.

## Extraction strategy — TRAILING slices, byte-identical, full-suite-gated

Extract from the END backward (each trailing slice has the fewest downstream escapes → smallest
output surface → easiest byte-identity). After each slice: full-suite green (known names only) → commit.

- [ ] **Slice 0 — characterize + freeze:** enumerate, for the region 4304→5279, every identifier that
  is (a) READ before written (→ TravelState input) and (b) written and used AFTER 5279 or is a Walker
  field (→ output/side-effect). Produce the exact input/output list. Deliverable: a comment block +
  the `TravelState` field list. No code move yet. Gate: build only.

- [ ] **Slice 1 — sneak/sprint/dive tail (≈4630→5279):** move the trailing sneak-brake + sprint + dive
  + lava + final input-emit block into `TravelActuator.driveTail(st)`; legacy calls it. Full-suite gate.

- [ ] **Slice 2 — drive/commandMove core (≈4470→4630):** move driveF/driveL/driveTargetYaw +
  camera-decouple + commandMove into the actuator. Full-suite gate.

- [ ] **Slice 3 — lane-keep/strafe + jump-timing (≈4304→4470):** move the remaining head of the tail.
  Now the WHOLE tail is `TravelActuator.drive(st)`; legacy tickInner ends at the actuator call.
  Full-suite gate + `./gradlew build`.

- [ ] **Slice 4 — wire AscendMovement to TravelActuator:** replace AscendMovement's stub with:
  episode tracking + CONFIRM(within→SUCCESS) + build TravelState + `TravelActuator.drive(st)` +
  RUNNING. Move the delegation branch to AFTER the tail (or keep at 4292 but have it own the whole
  tail). ascentSpeedArena → 2-leg A/B, ON leg byte-identical to OFF. Full-suite gate. **Faithful port.**

- [ ] **Slice 5 — task#82 timeout→UNREACHABLE:** the dead-zone fix — bounded episodeTicks without
  vertical progress → UNREACHABLE → forceFellOffPath. New task#82 RED→GREEN arena. **CLOSES task#82.**

- [ ] **Slice 6 — stairUpBreak/diagUp BREAK phase + live A/B + default-ON flip** (needs task#83 baseline
  refresh first for the flip). Live-screen-watch validation on the survival bot.

## Risk controls

- Each slice is independently revertible (its own commit); if full-suite shows a new name, `git revert`
  that slice and re-cut smaller.
- Do this WORK IN-SESSION (not via subagent) — the two Unit-2 subagents both returned prematurely and
  one re-implemented instead of extracting. Delicate byte-identical extraction needs direct control.
- Keep `walkerAscendMovement` default OFF until Slice 6; Slices 1-3 don't touch the flag at all (pure
  refactor of legacy — the machine isn't involved yet), so they can't change live behavior.
