# Executor per-move state machine — ASCENT family first (task#82)

Status: awaiting user review. Design decisions are FIXED (see task brief); this document formalizes
them. All code claims verified by reading `Walker.java` (5804 lines), `Move.java`, the ascent moves,
`BotConfig.java`, `WalkerConstants.java`, and `AgentGameTestTerrain.java` on 2026-07-15. File:line
anchors are current-tree.

> Implementation notes / deviations from the brief are collected in §10 — read them before coding.
> The most important: there is no `DiagUp.java`; the diagonal ascend move is
> `moves/DiagonalAscend.java` whose `name()` returns `"diagUp"`.

---

## 1. Goal & non-goals

**Goal.** Migrate only the ASCENT/CLIMB move-family (`stepUp`, `stairUpBreak`, `diagUp`) out of the
monolithic `Walker.tickInner` (`Walker.java:643`, ~4600 lines ending at `Walker.java:5257`) into a
self-contained per-move state machine in the Baritone `MovementState` form: a new `AscendMovement`
whose `updateState(ctx)` returns a `MovementStatus` and OWNS its own PREP→BREAK→ASCEND→CONFIRM
sequence, its own input emission (drive/jump/aim/break/place), and — the crux — its own
timeout→cancel. This closes task#82: a no-tool bot that lands ~1.2–1.5 blocks off a
`stairUpBreak`/`stepUp` node sits in the horizontal step-advance dead-zone
(`cur2 ∈ (REACH_DIST_SQ=0.45, OVERSHOOT_RESYNC_SQ=4.0)`, `WalkerConstants.java:204,229`), no recovery
gate covers the `+1`-vertical/no-`hCol` case, and — because A* reported `goalReached=true` — the
futile-search cap is blind, so the bot churns forever. `AscendMovement` returns `UNREACHABLE` on a
bounded timeout instead of churning, mapping to the existing re-route.

**Non-goals.** NOT touching any non-ascent move — walk, all descent/`fall`/`stepDown`, parkour,
`pillarUp`, bridge/place, all water/swim moves fall through to legacy `tickInner` byte-identically.
NOT a big-bang rewrite of `tickInner`; the delegation is a single narrow branch guarded by a
default-OFF flag, and legacy ascent recovery gates stay resident (dormant) until `AscendMovement` is
proven live. NOT solving descent (that is #51, a later migration that reuses this scaffolding). NOT
re-refreshing the regression baseline (that is task#83, a prerequisite — §8).

---

## 2. Background / problem (task#82)

### 2.1 The step-advance gates and the dead-zone

Each tick `tickInner` computes the horizontal squared distance from the foot to the current path node
`cur2 = dx*dx + dz*dz` (`Walker.java:1943`) and advances the `step` pointer on one of two conditions:

- `within` — arrival: `cur2 < REACH_DIST_SQ` (0.45) plus the vertical gate (`Walker.java:1985`).
- `passed` — pure-pursuit overshoot: the next node reads strictly closer, with an `overshot` branch
  that only engages once `cur2 > OVERSHOOT_RESYNC_SQ` (4.0) (`Walker.java:1993–2048`).

Between those two thresholds — `cur2 ∈ (0.45, 4.0)` — lies a **dead-zone**: too far for `within`, too
near for the overshoot re-sync. A bot pinned there advances neither pointer.

### 2.2 Why no recovery covers task#82

The ascent recovery family each has a gate that the task#82 pose misses:

| Recovery | Gate | Misses task#82 because |
|---|---|---|
| `ascentRamSlide` (`Walker.java:928–932`) | node **≥2 above** foot + `onGround` + no-step-progress | task#82 node is only **+1** above |
| `descentRamStuck` (`Walker.java:963+`) | node **1 below** + `horizontalCollision` | task#82 node is **above**, and there is **no `hCol`** (foot is short of the riser, not ramming it) |
| `stepUpFreeze` (`stepRamStuckTicks`, `WalkerConstants.java:248`) | GROUNDED **riser-RAM** (`hCol`) past `STEPUP_FREEZE_TICKS`=24 | requires `hCol`; a bot stalled 1.2 b short of the riser is not colliding |
| `fellOffPath` core (`Walker.java:1042`) | `|Δy| > maxJumpUp+2` (=3) | task#82 `|Δy|`=1 |
| `arcWedge` (`Walker.java:1019`, ON) | `arcWedgeTicks > ARC_WEDGE_TICKS` **while `hCol`** | no `hCol` → `arcWedgeTicks` never accrues |
| `walkerStepUpCrestReach` (DELETE-set, OFF) | `|dyNode| < 0.5` (topped out) + orbit stall | task#82 bot has NOT topped; it is short in XZ, not orbiting the crest |

So the `+1`, no-`hCol`, dead-zone-`cur2` pose falls through every gate.

### 2.3 The concrete signature

A `goto` whose plan reaches its goal returns a path whose tail is a `stairUpBreak` (`name()` =
`"stairUpBreak"`, `moves/StairUpBreak.java:93`) or `stepUp`. The bot arrives ~1.2–1.5 b off the stand
cell: `cur2 ≈ 1.48` — inside `(0.45, 4.0)`. The riser cells the edge planned to break
(`edge.toBreak` = `srcUp2` / `to` / `head`, `moves/StairUpBreak.java:65–88`) stay **solid** because
the break actuator only runs when the bot is close enough to aim, and the bot never closes the gap.
`within` can't fire (`cur2 > 0.45`), `passed` can't fire (next node is higher and further), `hCol` is
false so no ram gate arms. Because A* reported **`goalReached=true`**, the futile-search / no-path
recovery that would blacklist an unreachable node never engages — that path is treated as valid, so
the executor loops: re-derive same tail → same dead-zone → re-search churn, indefinitely. This is the
"within-gate dead-zone" stall.

### 2.4 Why a per-move machine, not another gate

The recovery family is already a **zoo** of ~8 instantaneous, mutually-blind gates
(`ascentRamSlide`, `descentRamStuck`, `verticalResync`, `arcWedge`, `arcProgWedge`,
`aboveNodeStall`, plus the relaxed-reach advances `crossedWalkNode`/`stepUpCrestReach`/
`waterWalkReach`/`waterStepDownFloat`), each patched for one live pose and each defeated by the
buoyancy/vertical bob that zeroes its `noStepProgressTicks` (documented at `Walker.java:210–226` and
across `BotConfig.java:1300–1660`). Adding a 9th gate for the `+1` case perpetuates the pathology. A
per-move machine that OWNS a timeout replaces the per-pose gate accretion with one bounded-liveness
guarantee: an ascent edge either completes or is declared `UNREACHABLE` within a fixed budget.

---

## 3. Architecture

### 3.1 The `Movement` abstraction

A new interface (package `net.magicterra.worlddriver.bot.movement`, alongside `Walker`):

```java
public interface Movement {
    /** Advance this move one tick against ctx; emit inputs via ctx; return status. */
    MovementStatus updateState(MovementContext ctx);
}

public enum MovementStatus {
    PREP,        // still aligning/positioning; not yet actuating the core maneuver
    RUNNING,     // actuating (breaking/jumping/rising); hold the step pointer
    SUCCESS,     // arrived on the destination stand cell → caller does step++
    UNREACHABLE, // bounded timeout with no actuation progress → caller re-routes (blacklist node)
    FAILED       // hard error (edge/world inconsistency) → caller re-routes
}
```

`SUCCESS` vs the two failure codes matters at the delegation point (§3.3): `SUCCESS` is a clean
pointer-advance; `UNREACHABLE`/`FAILED` both fold into the existing re-route but carry different
telemetry (`UNREACHABLE` = "could not close the gap within budget" — the task#82 case; `FAILED` =
"edge is malformed / world changed under us").

### 3.2 `MovementContext` — the data the machine owns

The context is derived from exactly the executor state the ascent path reads today. Rather than pass
`Walker`'s whole field set, `MovementContext` bundles:

- **Pose/velocity** — `Player p` (position, `getDeltaMovement`, `onGround`, `horizontalCollision`,
  `isInWater`), matching every read in the jump-timing block (`Walker.java:4291–4337`).
- **Edge/geometry** — the current `Move.Edge` (`Move.java:109`): `to` (stand cell), `toBreak`,
  `toPlace`, `move` name; plus the foot `BlockPos`, the resolved `wp`/node, `upDy`, `maxStepUp`,
  `maxJumpUp`, and the chain-context peek at `path.get(step-1/-2)` used by `chainAscend`
  (`Walker.java:4326–4332`).
- **World** — the `WorldView`/`Avatar` for `selectTool`/`aimAtBlock`/`breakHold`/`placeOn` and the
  solidity reads the BREAK phase needs (`WorldView.isSolid`, mirrors `StairUpBreak.eval`).
- **Constraints** — `ColumnRadius` / `LeashHardRadius` if active (the BREAK phase must respect them,
  per the brief), read the same way the planner/actuator do today.
- **Clock/counters the machine now OWNS** (moved off `Walker`'s field list, `Walker.java:159–226`):
  a per-episode tick counter, an actuation-progress marker, and the pillar-recover sub-state
  (`pillarRecoverLatch/Cell/PeakY/StallTicks`, `Walker.java:159–162`) that ASCEND absorbs (§4).
- **Input sink** — the `avatarForward/avatarJump/avatarSneak/aim` channel the machine drives (the same
  helpers `tickInner` calls, e.g. `avatarJump(a,…)` `Walker.java:670`).

The context is constructed once per delegated tick from live `Walker` state; the machine does not
retain references across ticks except through its own owned sub-state (which `Walker` holds on its
behalf while the flag routes to it, so a mid-episode flag flip cannot strand state).

### 3.3 The `tickInner` delegation point

A single branch, inserted where `tickInner` today resolves the current edge and BEFORE the ascent
jump-timing block (`Walker.java:4287`, the `Baritone MovementAscend jump-timing` comment):

```
if (BotConfig.walkerAscendMovement
        && edge != null
        && isMigratedAscent(edge.move)            // "stepUp" | "stairUpBreak" | "diagUp"
        && !p.isInWater()) {                        // water ascents stay legacy (own dig recovery)
    MovementStatus st = ascendMovement.updateState(ctx);
    switch (st) {
        case SUCCESS:      step++; /* fall into normal post-advance */ break;
        case RUNNING:
        case PREP:         return Step.WALKING;      // hold pointer, machine drove inputs this tick
        case UNREACHABLE:
        case FAILED:       // fold into the existing re-route: set fellOffPath, blacklist edge.to
                           forceFellOffPath = true;  break;
    }
}
```

- `isMigratedAscent` matches the three names — `stepUp`, `stairUpBreak`, `diagUp` — exactly the
  strings the recovery gates already test (`Walker.java:952` tests `wedgeEdge.move.equals("stepUp")
  || .equals("diagUp")`; `stairUpBreak` is the break-carrying sibling).
- **`SUCCESS → step++`** reuses the normal advance; no new pointer logic.
- **`UNREACHABLE|FAILED → fellOffPath`** reuses the proven backbone: `fellOffPath` (`Walker.java:1042`)
  → node blacklist + fresh foot-search (`Walker.java:1092+`, `1315`), and `safetyRepath`
  (`Walker.java:1210`). Nothing new is invented for re-route; the machine only supplies the *trigger*
  the dead-zone never produced.
- **flag OFF or non-migrated move or in-water** → the branch is skipped entirely and legacy
  `tickInner` runs byte-identically. This is the zero-cost no-op guarantee.

---

## 4. `AscendMovement` state machine

`AscendMovement` implements `Movement` as a scripted sequence. It holds only the per-episode state
above; every transition is a pure function of `MovementContext`.

```
        ┌────────┐  aligned within gate      ┌───────┐  riser cells cleared    ┌────────┐  foot on stand cell (|dyNode|<0.5, cur2<0.45)   ┌─────────┐
 enter →│ PREP/  │ ────────────────────────► │ BREAK │ ──────────────────────► │ ASCEND │ ──────────────────────────────────────────────► │ CONFIRM │→ SUCCESS
        │ ALIGN  │                            └───────┘                         └────────┘                                                  └─────────┘
        └────────┘                                │                                 │
             │ timeout (no gap-close)             │ break blocked/infinite          │ timeout (no rise)
             └───────────────► UNREACHABLE ◄──────┴─────────────────────────────────┘
```

### 4.1 PREP / ALIGN

Close the horizontal gap to the stand cell and square up on the cross-axis so the jump flies straight
at the step, not into a corner. This absorbs the KEEP alignment logic verbatim:
`pivotForStepUp` (`Walker.java:4150`), `STEPUP_AIM_TOLERANCE_DEG`=40 (`WalkerConstants.java:193`), and
the square-up gate `aligned` (`sideDist ≤ 0.2`, `|lateralMotion| ≤ 0.1`, `Walker.java:4333–4335`)
including the `chainAscend` loosening for consecutive same-direction steps (`Walker.java:4326–4332`,
`walkerChainMount`). **The task#82 fix lives here:** PREP runs a bounded timeout (a small tick budget
derived from `STEPUP_FREEZE_TICKS`=24 / the ~1.2 s a healthy `stepUp` closes in, `WalkerConstants.java:248`).
If the horizontal gap has not closed enough to actuate within the budget — the dead-zone signature,
`cur2` stuck in `(0.45, 4.0)`, no `hCol`, no progress — PREP returns **`UNREACHABLE`** instead of
pivoting forever. Transition to BREAK once within the align gate (or straight to ASCEND for a plain
`stepUp` with no `toBreak`).

### 4.2 BREAK

Only entered for edges with a non-empty `edge.toBreak` (`stairUpBreak`; a `stepUp`/`diagUp` has an
empty `toBreak`, `moves/StepUp.java`, `moves/DiagonalAscend.java`, so it skips straight to ASCEND).
Mine the riser cells the edge planned — `srcUp2` (source headroom), `to` (destination foot), `head`
(destination head) — in the order and with the reject-on-infinite-cost logic `StairUpBreak.eval`
already encodes (`moves/StairUpBreak.java:65–90`), driving `selectTool`/`aimAtBlock`/`breakHold`.
Respect `ColumnRadius`/`LeashHardRadius` when active (per brief). Absorbs the anti-suffocation ceiling
dig discipline from the pillar-recover actuator (`Walker.java:4264–4271`: clear the head cell first,
on the global `allowBreak` switch, EXEMPT from per-goto `forbidDig` because suffocation is death).
BREAK returns `RUNNING` while a cell is still being broken (holding the step, exempting the anti-stuck
burst exactly as `breakingEdge` does today, `Walker.java:900`), and `UNREACHABLE` if a target cell is
an unbreakable hazard/fluid (mirroring `StairUpBreak.eval` returning `null`) or the per-riser dig
budget is blown (`WATER_CLIMB_DIG_COMMIT_CAP` is the dry analogue reference, `WalkerConstants.java:518`).
Transition to ASCEND once all planned cells read clear.

### 4.3 ASCEND

Rise into the cleared cell. Absorbs the KEEP jump-timing block verbatim — `dryStepUp` /
`ascendJumpReady` / `sprintAscend` (`Walker.java:4291–4337`), the early-launch-with-sprint composition
that `ascentSpeedArena`/`diagonalAscentSpeedArena` validate, and `stepUpJump` (`Walker.java:4931`). For
a `+1` step whose landing overshoots a single jump because the bot slid back (the `ascentRamSlide`
condition, node ≥2 above, `Walker.java:928`), ASCEND drives the pillar-recover actuator it now owns
(`pillarRecoverLatch`, `Walker.java:4249–4284`; `PILLAR_RECOVER_TICKS`=14, `PILLAR_NORISE_GIVEUP`=50,
`WalkerConstants.java:280,286`) — place-beneath-at-apex to lift back onto the column. ASCEND returns
`RUNNING` while airborne/rising, and `UNREACHABLE` if no net rise occurs within the pillar no-rise
budget (the canopy/overhang trap, `WalkerConstants.java:281–286`). Transition to CONFIRM once the foot
reaches the node Y.

### 4.4 CONFIRM

Verify arrival: foot on the stand cell, `|dyNode| < 0.5`, `cur2 < REACH_DIST_SQ` (the real `within`).
Return **`SUCCESS`** → the delegation point does `step++`. If the bot topped out but orbits the crest
in the dead-zone (the `crestOrbit`/`stepUpCrestReach` pose, `Walker.java:186`,
`WalkerConstants.java:337–347`), CONFIRM's own arrival tolerance covers it structurally — this is why
the speculative `walkerStepUpCrestReach` gate becomes DELETE-able (§5): the machine's CONFIRM replaces
the relaxed-reach patch. If CONFIRM cannot ratify arrival within a short budget, return `UNREACHABLE`.

### 4.5 KEEP logic absorbed vs. delegated-to-backbone

- **Absorbed into `AscendMovement`:** the jump-timing block (PREP+ASCEND), `chainAscend`
  (PREP), the pillar-recover actuator (ASCEND), `pivotForStepUp` alignment (PREP), the
  `StairUpBreak` mining order + anti-suffocation ceiling dig (BREAK).
- **Left in the backbone (machine only triggers it):** `fellOffPath` / node blacklist /
  `safetyRepath` (`Walker.java:1042,1092,1210,1315`) — `UNREACHABLE`/`FAILED` route through these
  unchanged. `arcWedge` / `aboveNodeStall` stay resident (dormant behind the flag) as the legacy
  safety net until the machine is proven; see §8 open question on their redundancy.

---

## 5. Migration & deletion plan

Two disjoint sets, from the sonnet audit. All line numbers verified current-tree.

### KEEP — migrate faithfully (battle-validated, gametest-backed or most-cited)

| Logic | Location | Default | Rationale |
|---|---|---|---|
| stepUp/diagUp jump-timing (`dryStepUp`/`ascendJumpReady`/`sprintAscend`) | `Walker.java:4291–4337` | — | Most-cited; validated by `ascentSpeedArena` (`AgentGameTestTerrain.java:871`) + `diagonalAscentSpeedArena` (`:967`) |
| `chainAscend` (chain-mount loosen) | `Walker.java:4326–4332` (`walkerChainMount`) | ON | §92 chain-mount, live steep-climb lane |
| `pivotForStepUp` align | `Walker.java:4150` | — | `STEPUP_AIM_TOLERANCE_DEG`=40, prevents corner-bonk |
| `ascentRamSlide` slide-back fold | `Walker.java:928–932` | — | Steep √2 staircase recovery; the ≥2-above semantics ASCEND absorbs |
| `arcWedge` (Phase-3 bob-immune ram wedge) | `Walker.java:1019`; `walkerArcLengthWedge` `BotConfig.java:1641` | **ON** | Structural bob-immune wedge; kept resident as legacy net |
| `aboveNodeStall` recover | `Walker.java:1034`; `walkerAboveNodeStallRecover` `BotConfig.java:1881` | **ON** | Above-node stall → fellOffPath |
| pillar-recover actuator | `Walker.java:4249–4284`; `PILLAR_RECOVER_TICKS`/`PILLAR_NORISE_GIVEUP` `WalkerConstants.java:280,286` | — | In-place pillar-up + anti-suffocation ceiling dig |
| `fellOffPath`/`safetyRepath` backbone | `Walker.java:1042,1210` | — | Unconditional re-route spine; `UNREACHABLE` maps here |

### DELETE — remove, do NOT migrate (unvalidated/speculative/dead)

`AscendMovement`'s own timeout→cancel replaces the recovery these were meant to provide.

| Flag | Location | Default | Audit rationale |
|---|---|---|---|
| `walkerVerticalResync` | `BotConfig.java:1326` | **false** | "could NOT be reproduced deterministically"; its arena `descentOvershootResyncArena` was deleted; zero coverage |
| `walkerAscentRamJitterImmune` | `BotConfig.java:1596` | **false** | Never flipped ON; jitter-immune slide fold that ASCEND's owned timeout subsumes |
| `walkerStepUpCrestReach` | `BotConfig.java:1562` | **false** | Crest-orbit relaxed-reach patch; replaced by CONFIRM's arrival tolerance (§4.4) |
| `walkerLevelRiserJump` | `BotConfig.java:1350` | **false** | Speculative level-node ram jump; never validated |
| `walkerPadRamBreak` | `BotConfig.java:1374` | **false** | Lateral lily-pad break (water); not ascent, never validated |
| `walkerStickyDig` (already killed §66) | `BotConfig.java:1892` | **false** | Dead; killed in §66, remove residue |

Deletion touches the flag declarations, their `mc.bot.setting` wiring, the gates that read them in
`Walker.java`, and — importantly — `walkerStepUpCrestReach`'s dedicated arena `stepUpCrestOrbitArena`
(`AgentGameTestTerrain.java:499`) becomes vestigial; it must be re-pointed at the new
`AscendMovement` CONFIRM path or retired (see §6, §9).

### Coexistence behind `walkerAscendMovement`

New `public static volatile boolean walkerAscendMovement = false;` in `BotConfig`, wired to
`mc.bot.setting`. OFF → the delegation branch (§3.3) is skipped; every ascent tick runs the legacy
path; the KEEP and (still-present until the DELETE step) legacy gates behave exactly as today —
**byte-identical no-op**. The DELETE set is removed in its own late unit (§9 Unit 5) only after the
machine is proven, so the gray rollout never depends on removing a safety net before its replacement
is trusted.

---

## 6. Validation strategy

Replay-driven, matching the house discipline (`Live/replay = truth, arena = regression guard`).

### 6.1 RED → GREEN: the task#82 dead-zone arena

Author a new `@GameTest` arena mirroring `stepUpCrestOrbitArena` (`AgentGameTestTerrain.java:499–622`),
which already demonstrates the exact scaffolding:

- Build a disjoint absolute region; place a `stairUpBreak`/`stepUp` tail whose stand cell is `+1`
  above the foot with a **solid** riser (`edge.toBreak` non-empty).
- Hold the bot at the task#82 pose — `cur2 ≈ 1.48` (in `(0.45, 4.0)`), `|dyNode|`≈1, **no `hCol`** —
  by `setPos`/`setDeltaMovement(0,0,0)` before each tick (the static stand-in for the live bob, exactly
  `stepUpCrestOrbitArena:567–572`).
- Adopt a scripted plan via **`walker.beginReplay(w, plan, planEdges, goal, node)`**
  (`Walker.java:468`): `replayMode` DISABLES A*/repath so the OFF leg is a TRUE permanent stall with no
  escape muddying the A/B (`stepUpCrestOrbitArena:557–560`).
- **Two legs, like the crest arena:** leg 0 = `walkerAscendMovement` OFF must WEDGE (step never
  advances, mirroring `advanced[0]` must be false, `:599`); leg 1 = ON must resolve — here "resolve"
  means the machine returns `UNREACHABLE` and the delegation folds to `fellOffPath` (assert the node
  is blacklisted / the pointer is not pinned for the whole window, zero churn).
- Because `beginReplay` kills repath, the GREEN assertion checks the `MovementStatus`/re-route
  trigger directly rather than a successful climb (the arena has no live A* to re-route into). A
  companion test using the **recovery-ON seam `adoptForTest`** (`Walker.java:504`) exercises the full
  `UNREACHABLE → fellOffPath → fresh foot-search` path with the machinery left on.

### 6.2 Regression guards

The existing ascent arenas gate the KEEP migration — they must stay GREEN with the flag ON, proving
`AscendMovement` reproduces legacy jump-timing:

- `ascentSpeedArena` (`AgentGameTestTerrain.java:871`) — cardinal `stepUp` speed must not collapse
  (`:948`).
- `diagonalAscentSpeedArena` (`:967`) — diagonal `diagUp` speed (`:1033`).
- `selfShaftDigUpArena` (`:797`) — `stairUpBreak` self-shaft carve-up.
- `stepUpCrestOrbitArena` (`:499`) — re-purposed to assert CONFIRM covers the crest orbit (its
  DELETE flag `walkerStepUpCrestReach` is gone).

Register the new arena in the validation-scripts / gametest set (per the §67 memory rule that a new
suite must be registered or it silently never runs).

### 6.3 Boundaries

The gametest/replay path is **client-only** (`ReplayProcess` javadoc, `process/ReplayProcess.java:15`,
"replay mode … used by `mc.debug.replay`"); a server FakePlayer is immune to some physics/damage, so
the arena is a deterministic regression guard, NOT the acceptance gate. **Live replay A/B is the
ultimate proof:** capture the task#82 stall with `PathArchiveRecorder`
(`bot/debug/PathArchiveRecorder.java`), replay it OFF vs ON via `mc.debug.replay` (`ReplayTool.java`),
and confirm the ON run declares `UNREACHABLE` and re-routes with zero churn where OFF loops. The flag
flips to default-ON only on a clean live A/B.

---

## 7. Prerequisites & dependencies

- **task#83 — refresh `config/worlddriver/replays/baseline-flags.json` FIRST (blocking).** Verified
  stale: the file was committed **2026-06-28 13:09** (git), predating the 2026-07-04 batch flag-flip by
  6 days; it lists flags including `walkerAscentRamJitterImmune`, `walkerStepUpCrestReach`,
  `walkerVerticalResync`, `walkerLevelRiserJump`, `walkerPadRamBreak` (the DELETE set) plus KEEP flags
  at values that no longer match shipped config, so it never gated the current build. The corpus
  regression run must be re-baselined against current shipped config before this refactor lands, or
  the A/B has no valid regression floor. This spec does NOT re-solve task#83; it declares the
  dependency. (Note: several flags this refactor DELETES appear in the stale baseline — task#83's
  refresh must drop them, so the two tasks are coupled: refresh baseline → then delete flags.)
- **#82 — fixed by this refactor** (the whole point).
- **#51 — descent migration (later).** This establishes the `Movement`/`MovementContext`/`MovementStatus`
  scaffolding that a future `DescendMovement` reuses; descent is explicitly out of scope here.
- **#52 — self-test framework.** The deterministic-arena discipline used in §6 is the same one #52
  formalizes; this refactor consumes the existing `beginReplay`/`adoptForTest` seams, it does not
  build new test infra.

---

## 8. Risks & open questions

1. **KEEP-set redundancy (must resolve before finalizing what `AscendMovement` absorbs).**
   `ascentRamSlide` (`Walker.java:928`), `arcWedge` (`:1019`, ON), and `aboveNodeStall` (`:1034`, ON)
   are three overlapping recoveries for adjacent ascent-stall poses. It is not yet known which are
   load-bearing vs. shadowed by the others. **Open question:** run a live A/B toggling each in
   isolation on the captured ascent-stall corpus to learn which the machine must faithfully absorb and
   which can also retire. Do NOT delete any KEEP recovery on suspicion — the DELETE set is limited to
   the never-validated default-OFF flags. Resolve this before committing the final absorbed-logic list
   for ASCEND.
2. **Stale-baseline dependency (§7).** If task#83 slips, the refactor has no valid regression gate and
   cannot be safely flipped ON. Hard-block the flip on a refreshed baseline.
3. **Delegation-point regression surface.** The branch (§3.3) sits in the hottest method in the mod
   (`tickInner`, `Walker.java:643`). Even with the flag OFF the branch is evaluated each ascent tick;
   `isMigratedAscent` must be a cheap string check and the OFF path must provably not allocate
   `MovementContext`. The `SUCCESS → step++` path must exactly reproduce the legacy post-advance
   (verify against `ascentSpeedArena`).
4. **In-water ascents excluded.** The delegation gates on `!p.isInWater()`; water bank-climb-out keeps
   its own dig recovery (`WATER_CLIMB_*`, `WalkerConstants.java:499–573`). A move that starts dry and
   enters water mid-edge must fall back cleanly — the machine returns and legacy takes over next tick.
5. **`stepUpCrestOrbitArena` coupling.** Deleting `walkerStepUpCrestReach` invalidates that arena's
   two-leg A/B; it must be re-authored against CONFIRM or retired in the same unit, or CI goes red.

---

## 9. Decomposition into implementable units

Each unit is independently testable and lands behind the default-OFF flag until Unit 4 proves the fix.

- **Unit 1 — scaffolding + no-op wiring.** Add `Movement` interface, `MovementStatus` enum,
  empty `AscendMovement`, `MovementContext`, and `BotConfig.walkerAscendMovement = false` (wired to
  `mc.bot.setting`). Insert the delegation branch (§3.3) that, with the flag OFF, is provably skipped.
  Test: full ascent-arena suite stays GREEN (byte-identical); a unit test asserts the OFF branch does
  not allocate a context.
- **Unit 2 — PREP/ALIGN + `stepUp` delegation.** Implement PREP (absorb `pivotForStepUp` +
  jump-timing align) and ASCEND for plain `stepUp` (empty `toBreak`), wire `SUCCESS → step++`. Test:
  `ascentSpeedArena` GREEN with the flag ON (jump-timing faithfully reproduced).
- **Unit 3 — BREAK phase for `stairUpBreak` (and `diagUp`).** Implement BREAK (mine `srcUp2`/`to`/
  `head` respecting ColumnRadius/LeashHardRadius + anti-suffocation ceiling dig) and add `diagUp` to
  the migrated set. Test: `selfShaftDigUpArena` + `diagonalAscentSpeedArena` GREEN with the flag ON.
- **Unit 4 — timeout → `UNREACHABLE` + task#82 RED→GREEN arena.** Add the PREP bounded-timeout that
  returns `UNREACHABLE`, wire `UNREACHABLE|FAILED → fellOffPath`, author the task#82 dead-zone arena
  (§6.1, two-leg `beginReplay`). This is the unit that closes task#82. Test: OFF leg WEDGES, ON leg
  re-routes with zero churn.
- **Unit 5 — CONFIRM crest coverage + delete the unvalidated flags.** Finalize CONFIRM arrival
  tolerance so it subsumes the crest orbit, then remove the DELETE set (`walkerVerticalResync`,
  `walkerAscentRamJitterImmune`, `walkerStepUpCrestReach`, `walkerLevelRiserJump`, `walkerPadRamBreak`,
  `walkerStickyDig` residue) and re-point/retire `stepUpCrestOrbitArena`. Depends on task#83 baseline
  refresh landing first. Test: suite GREEN with the DELETE flags gone.
- **Unit 6 — live A/B + default-ON flip.** Capture/replay the task#82 stall (`PathArchiveRecorder` →
  `mc.debug.replay`), A/B OFF vs ON, resolve the §8.1 KEEP-redundancy open question, then flip
  `walkerAscendMovement` default ON. Retire the now-dormant legacy ascent recovery gates only after
  this proves out.

---

## 10. Implementation notes / deviations (code contradicted the brief)

- **No `moves/DiagUp.java`.** The diagonal ascend move is `moves/DiagonalAscend.java`; its `name()`
  returns `"diagUp"` (`moves/DiagonalAscend.java:70`). The migrated-set string match is on the move
  NAME `"diagUp"`, not a filename. (`DiagonalAscend.eval` also carries a water/steep penalty,
  `:29–36` — irrelevant to the executor migration, which keys on the runtime edge name.)
- **`Walker.java` is 5804 lines, not 5254.** `tickInner` spans `643`→`~5257` (next method `terminal`
  starts `5258`). The brief's interior anchors nonetheless matched the current tree
  (jump-timing `4291–4337`, pillar-recover `4249`, `ascentRamSlide` `928`, `arcWedge` `1019`,
  `aboveNodeStall` `1034`, `fellOffPath` `1042`, `safetyRepath` `1210`); only the file-length figure
  and the "~4600 lines" gestalt were off. `within`/`passed` are at `1985`/`1993`, `cur2` at `1943`,
  `REACH_DIST_SQ`=0.45 / `OVERSHOOT_RESYNC_SQ`=4.0 in `WalkerConstants.java:204,229`.
- **Ascent move-family scope is narrower than "all ascend moves."** `Move.build()`
  (`Move.java:298–410`) also enumerates `StepUp2` (+2, horse/jump-boost only, inert on foot,
  `:306`) and `ParkourAscend` (`:362–365`). These are **NOT** in the migrated set — the brief's three
  names (`stepUp`, `stairUpBreak`, `diagUp`) are the scope; `stepUp2`/`parkourAscend*` fall through to
  legacy. Confirmed against the recovery gates, which likewise exclude `parkour*`
  (`Walker.java:930,937`).
- **All DELETE flags confirmed default-`false`** in `BotConfig.java` (`1326,1350,1374,1562,1596,1892`);
  all KEEP flags confirmed default-`true` (`walkerArcLengthWedge` `1641`, `walkerAboveNodeStallRecover`
  `1881`). No decided-design assumption changed.
