# LLM Navigation Intent Layer — Design

Date: 2026-07-04
Branch: `feat/llm-intent-layer`
Status: DRAFT (awaiting user review)

## 1. Problem

Every complex navigation scenario the LLM wants to express ("沿河岸走", "带 B 去村庄但别离太远",
"躲骷髅逃回基地", "漂浮药水上空岛", "铁镐挖到 Y=-54"…) currently requires a **bespoke Java
`BotProcess`** or ad-hoc tuning. Each Process hand-wires the same five things together: a
goal/target, cost-shaping bias, hard constraints, termination, and re-plan/reflex behaviour.
This does not scale — new intent = new engine code.

Three pain points, in priority order:

- **A (coverage / extensibility, TOP PRIORITY)** — the LLM cannot express *novel* navigation
  intents without us writing new Java. We want a **general composition mechanism**.
- **B (execution-time robustness / closed loop)** — the LLM fires a verb then goes "blind"; it
  cannot perceive a changed world (skeleton flanks, companion walks off, river widens) and adapt
  mid-flight.
- **C (constraint expressiveness)** — no way to state constrained compound intents
  ("head to X but stay leashed within N blocks of player Y", "avoid region while reaching Z").

## 2. Current architecture (grounded in code)

Bottom → top:

1. **Moves** (`bot/pathfinder/moves/*` — `Walk`, `Parkour2/3`, `StepUp/2`, `SwimTraverseBreak`,
   `PillarUp`, `ParkourPlace`, `WaterBucketFall`, …) — atomic A* edges with `cost()` + executor.
2. **PathFinder (A*)** + **`Goal`** catalog (`bot/Goal.java`) — already mirrors Baritone:
   `Block/Near/XZ/YLevel/RunAway/Composite/GetToBlock/TwoBlocks/Axis/Inverted/StrictDirection`.
   Heuristic `estimate()` + `reached()`. Cost taxes (`waterCellTax`, `descendTax`, `dangerCost`,
   hazard) are **hardcoded inside `PathFinder.Search`**.
3. **Walker** (`bot/movement/Walker.java`) — pure-pursuit executor. Already carries the health
   signals: `stuckTicks`, `noStepProgressTicks`, `churnBase/churnWindowTicks/churnEscapes`
   (net-displacement window), `boxedEscalateUntilTick` (PROACTIVE pinch escalation). These drive
   **internal** recovery only — they are not surfaced.
4. **Processes** (`bot/process/*` — `GotoProcess`, `FollowProcess`, `MineProcess`,
   `RunAwayProcess`, `ElytraProcess`…) — stateful behaviours. `GotoProcess` holds a **`final Goal`**
   and calls `walker.setGoal(goal)` **once** in the constructor.
5. **Scheduler** (`bot/scheduler/*`) — `ProcessScheduler` owns the *movement channel*; `Chain`s bid
   `priority()` each tick (altoclef TaskChain model), highest wins, `HYSTERESIS` prevents thrash.
   Reflex chains (`Dodge/Panic/Retreat/Combat/Bunker/DuskSecure`) self-activate and preempt
   `UserTaskChain` (which holds the LLM's foreground `BotProcess`), handing back via
   `onInterrupt/onResume`. **This is the reflex layer — already complete.**
6. **MCP verbs** (`mc.bot.goto/follow/mine/runAway/elytra…`) + **Skill library** (Voyager JS,
   `script/SkillLibrary.java`) + **Playbook**.
7. **Event push** — `AgentApi.emit(type,pos,data)` → ring buffer + fan-out to push subscribers →
   surfaces to the LLM as `<channel source="agent-driver">` tags. `emitExternal(...)` is the public
   hook that threat/damage/chat detectors already feed. **This is the escalation channel — already
   built; today it carries no `intent.*` events.**

## 3. Core abstraction: `NavigationIntent`

Factor the bespoke Processes into **orthogonal, data-driven primitives** interpreted by ONE generic
process. An intent is a value object (JSON from the LLM):

```
Intent {
  target:      TargetProvider     // where to go (static | dynamic | derived | region | direction)
  bias:        CostModifier[]     // soft A* cost shaping (composable stack)
  capability:  CapabilityProfile  // which Moves are enabled + required items
  constraints: Constraint[]       // hard prune (leash radius, Y floor/ceiling, forbid water, require tool)
  terminate:   Terminator[]       // reached | condition | timeout | predicate (see-sky)
  watch:       WatchPolicy        // (phase B) escalateOn reasons + optional heartbeat
}
```

Every listed scenario decomposes into a combination of existing primitives + a few genuinely new
physical primitives (a levitation-ascend Move, a dig-column Move, a `riverbank` derived target).
New physical primitives are written once and are reusable forever; new *scenarios* are pure LLM
parameterization — zero Java.

### 3.1 Primitive taxonomy (phase A target)

- **TargetProvider**
  - `static` — wraps existing `Goal.Block/Near/XZ/YLevel/GetToBlock/TwoBlocks/Axis`.
  - `dynamic` — re-evaluated each re-solve from live world state (`entity:<selector>` → follow a
    player/mob; target = its current cell). Backs "跟玩家 A".
  - `derived` — nearest cell matching a predicate (`feature:riverbank` = land cell adjacent to
    water; `surface` = lowest cell with sky access above). Backs "沿河岸走", "挖到地面".
  - `region` / `direction` — wraps `Near`-of-region / `StrictDirection`.
- **CostModifier** (NEW: extract the hardcoded taxes into a composable `CostModifier` interface the
  `Search` consults per edge)
  - `avoid(region | entityLOS, weight)` — penalize cells inside a danger region / in a mob's line of
    sight. Backs "躲骷髅".
  - `leash(anchor, softRadius, weight)` — penalize cells far from a moving anchor. Soft half of C.
  - `preferY(band, weight)`, `hazardWeight(scale)`, `blockPreference(...)`.
  - Existing `waterCellTax`/`descendTax`/`dangerCost` become built-in modifiers (behaviour-preserving
    refactor, guarded by the conformance/replay suite).
- **CapabilityProfile** — a Move allow/deny set + item preconditions. Profiles: `walk` (default),
  `parkour` (enable Parkour moves), `dig-down`/`dig-up` (column Moves + require a specified tool,
  e.g. `iron_pickaxe`), `swim`/`dive`, `levitation-ascend` (consumable-gated NEW Move), `elytra`
  (routes to the existing `ElytraProcess`/`ElytraPathfinder`). Backs "铁镐挖到 Y=-54", "跑酷过河",
  "漂浮药水上空岛", "鞘翅回主岛".
- **Constraint** (hard prune) — `leashHard(anchor, maxRadius)`, `yFloor/yCeil`, `forbidWater`,
  `requireItem`. Hard half of C.
- **Terminator** — `reached` (default from goal), `condition(predicate)` (e.g. companion also within
  N of the destination — backs "带路但别太远"), `timeout(ticks)`, `predicate(see-sky)`.

## 4. Phase A — the deliverable

Build the generic **`IntentProcess`** + primitive taxonomy, plus a single `mc.bot.navigate{intent:…}`
verb. `IntentProcess`:

- holds a **mutable** `Intent` (unlike `GotoProcess`'s `final Goal`);
- each **re-solve** derives the concrete `Goal` (from `TargetProvider`) + a composed `CostModifier`
  closure, hands them to `PathFinder`, drives `Walker` exactly as `GotoProcess` does today;
- re-solve cadence is **dirty-triggered** (target moved past threshold / path-local world edit /
  cost field changed), not fixed-interval;
- exposes `amend(patch)` → mutate the intent in place + `walker.forceRepath()` (reusing the existing
  `onResume` seam) — **no cancel/re-attach**, so mid-flight change never triggers the water-restart
  sink death.

**Compatibility:** existing `goto/follow/runAway` verbs are re-expressed as thin adapters that build
an `Intent` and start an `IntentProcess`. `mine/build/craft/...` are untouched (not navigation).
Elytra capability delegates to the existing elytra planner rather than reimplementing it.

## 5. Phase B — closed loop (sketch, deferred to its own spec)

Almost free once A lands, because the intent is now re-solved data, not a frozen plan.

- **`IntentSupervisor`** — a `Chain` that bids `priority()==0` (never seizes the channel; observe +
  emit only), so it gets `(mc,w,st)` + the scheduler reference every tick, client and server.
  Reads the **already-existing** health signals (Walker `churnEscapes/noStepProgressTicks/stuckTicks`,
  `ProcessScheduler.lastPriorities()` history, intent-specific predicates) and maps them to a small,
  **scenario-agnostic** escalation-reason enum:
  1. net-displacement ≈ 0 (churn), 2. segment/terminator unreachable, 3. reflex saturation,
  4. constraint-margin breach, 5. capability exhaustion.
- On a latched reason (after engine self-recovery has had its first crack — supervisor sits ABOVE
  the recovery counters so we don't spam), `emitExternal("intent.escalation", pos, json)` over the
  existing push channel. The json carries a situation snapshot (pose + `mc.observe.threats` slice +
  target delta + what recovery was tried + suggested options), so the wake carries its own "why".
- LLM (idle-waiting on events per the no-idle-true-wait rule) decides at strategic altitude → either
  `amend`s the running intent (cheap, no restart) or drops to the Skill sandbox.

## 6. Phase C — constraints (mostly falls out of A)

`leash`/`avoid`/`yFloor`/`requireItem` are already A primitives (soft = CostModifier, hard =
Constraint). C is: (a) make the `mc.bot.navigate` schema express these ergonomically, (b) a couple of
compound terminators (`condition`). No separate engine layer.

## 7. Testing strategy (honors the hard memory rules)

- **live/replay is the acceptance truth, never GameTest-green** (`feedback_live_replay_is_truth`).
- The tax → `CostModifier` extraction is a **behaviour-preserving refactor**: gate it with the
  existing conformance/replay suite (`scripts/pmcs`, `path-replay`, `mc.debug.replay`) — the same
  paths must plan byte-identically before/after.
- Each new primitive gets a deterministic **replay** A/B from a real envelope (not a synthetic arena
  built from assumptions), plus the mandatory live-screen-watch channel during live runs.
- New Moves (levitation-ascend, dig-column) validated live A/B + GameTest regression guard (guard,
  not acceptance).

## 8. Build sequence (phased, each independently shippable)

- **A0** — extract `CostModifier` interface; migrate `waterCellTax`/`descendTax`/`dangerCost` behind
  it. Conformance/replay must stay identical. (Pure refactor, unblocks everything.)
- **A1** — `Intent` value type + `IntentProcess` (mutable goal, dirty re-solve) with `static` target
  + `walk` capability only. Re-express `goto` as an adapter. Prove parity with `GotoProcess` on
  replay corpus.
- **A2** — `CapabilityProfile` (dig-down/up + tool requirement; parkour gate). New dig-column Moves.
  Scenarios: "铁镐挖到 Y=-54", "挖到地面", "跑酷过 2 格河".
- **A3** — `dynamic` + `derived` targets (`entity:` follow, `riverbank`/`surface`). Re-express
  `follow`. Scenarios: "跟玩家 A", "沿河岸走".
- **A4** — soft `CostModifier`s exposed to the verb (`avoid`, `leash`, `preferY`) + hard
  `Constraint`s. Scenarios: "躲骷髅逃回基地", "带 B 去村庄别太远" (soft leash), constraint half of C.
- **A5** — special-capability delegation: `elytra` (existing planner), `levitation-ascend` (new Move).
  Scenarios: "鞘翅回主岛", "漂浮药水上空岛", "游回水下基地".
- **B** — `IntentSupervisor` + `intent.escalation` + `amend` verb (own spec after A).
- **C** — verb schema ergonomics + compound terminators (own spec, small).

Each phase: replay/live A/B before merge, no arena-green acceptance.
