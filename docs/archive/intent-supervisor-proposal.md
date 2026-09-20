# An escalation channel from the engine to the caller — proposed, never built

> **Archived proposal, written 2026-07-09, in Chinese. This design was never implemented, and
> none of the types it names exist.**
>
> The proposal was a low-priority chain that would never take the body, would read health signals
> the engine already computes — churn counters, stall counters, search statistics, reflex
> occupancy, air supply, fall distance — map them to a small set of named escalation reasons, and
> push an event to the caller so that a language model could change its conditions or its goal
> instead of waiting for a timeout. It also proposed two survival reflexes and a verb for amending
> a running intent in place.
>
> It is kept because the problem it names is real and is stated better here than anywhere else:
> the engine knows when it is unhealthy, the counters all exist, and there was no channel by which
> that reached the caller — or worse, the caller was told the wrong thing, because a give-up was
> being reported as an arrival.
>
> That last defect was fixed directly rather than through a supervisor: the walker now classifies
> and names its exit, described in `docs/design/scheduler-semantics.md`. The "tell the caller its
> conditions stopped working" half was answered by the route events in
> `docs/design/route-selection.md`, which fire only for conditions the caller declared. Nothing was
> built for the remaining reasons, and no amend verb exists.

## 1. The problem, from the evidence

Once an intent became a piece of data the engine could re-solve, the loop was still open at one end:
when the engine's own recovery failed, the caller had no way of knowing. It could only wait for a
timeout or have a human notice. Four classes of death or paralysis had already been demonstrated in
live runs.

- **Drowning on arrival.** An XZ goal that lands in the middle of a lake reports arrival, the driver
  goes passive as its discipline requires — it does not surface on its own, because the driver never
  moves while idle — and a bot in survival sinks and drowns. Demonstrated again in a live run on
  2026-07-07, at the end of a completed journey through the mountains, with the log line
  `Player137 drowned`.
- **Wandering somewhere lethal.** A best-effort or escape segment walks the bot into fatal terrain:
  wandering along a cliff edge, or falling to its death when levitation expires, which is how the bot
  in one experiment died. The void falls in `descentYawArena` between 2026-07-02 and 2026-07-07 are
  the same failure reproduced in an arena — nothing called a halt once the bot left the walkable
  region, and the anti-stuck machinery burned its budget in mid-air.
- **Silent paralysis.** Churn and limit cycles are partly caught by the deadlock breaker, but a
  pathological far-away goal behind a wall, where the charge is already at its ceiling and cannot push
  further, just grinds slowly on the spot. The caller has no way to know it should change strategy.
- **A false arrival from the anti-spin exit.** A best-effort termination still returned arrival, so
  everything upstream read a failure as a success.

What they have in common: **the engine knows it is unhealthy — all the counters exist — and has no
channel for reporting that upwards; or, worse, it reports the wrong conclusion.**

## 2. The design

```
IntentSupervisorChain (priority()==0, never takes the slot; both client and server)
  ├─ reads existing health signals every tick (no new probes):
  │    Walker: churnEscapes / noStepProgressTicks / stuckTicks(monotonic) /
  │            lastStats(expanded, goalReached, pathLen) / the step's terminal state
  │    IntentProcess: terminator state / target delta / constraint margin
  │    Avatar/Player: y velocity, inWater/air supply, fall distance, HP delta
  │    ProcessScheduler: lastPriorities() history (for detecting reflex saturation)
  ├─ maps them to a scenario-agnostic EscalationReason:
  │    CHURN_NET_ZERO / TARGET_UNREACHABLE / REFLEX_SATURATION /
  │    CONSTRAINT_MARGIN / CAPABILITY_EXHAUSTED / ARRIVAL_HAZARD / LETHAL_TRAJECTORY
  ├─ latches and applies a cooldown (the engine's own recovery runs first; the same reason
  │    is not re-emitted while its cooldown is active)
  └─ emitExternal("intent.escalation", pos, snapshot-json)
       └─ the caller receives the push → amend (change a running intent in place),
          or cancel and issue a new intent, or run a sandboxed skill
```

No executor behaviour is added; the supervisor only observes and reports. The one exception is the
pair of life-saving reflexes in section 4, which are **independent reflexes** on the existing reflex
channel and are not part of the supervisor.

## 3. The reasons and their criteria, all built on counters that already exist

| Reason | Criterion (draft; thresholds to be calibrated against replays) | Existing signal |
|---|---|---|
| CHURN_NET_ZERO | net displacement over a time window under 8 blocks while the charge has already escalated to its ceiling | the deadlock breaker's `CHURN_WINDOW` mechanism |
| TARGET_UNREACHABLE | N consecutive searches with `goalReached=false` and a diminishing gain on the best-effort segment | `lastStats` plus `chooseSegment` |
| REFLEX_SATURATION | reflex slot occupancy over a window above a threshold, say 50% | `lastPriorities()` |
| CONSTRAINT_MARGIN | a hard constraint such as the leash or the y floor is pinned at its edge continuously, with a margin under 1 block | `SearchProfile` constraints |
| CAPABILITY_EXHAUSTED | the goal is reachable only through a capability that has been gated off — the only route requires digging while digging is forbidden | the search's prune statistics, which need one new counter; the only addition on this list |
| ARRIVAL_HAZARD | at the instant termination is decided, the destination cell is dangerous: in water without a dive intent, adjacent to lava, or in mid-air | `isInWater`/`isHazard` plus the intent's opt-in |
| LETHAL_TRAJECTORY | an unplanned fall whose `fallDistance` exceeds what is survivable, or a y below the walkable region's floor | `fallDistance` plus the path envelope |

**The false arrival is fixed separately**: the anti-spin and best-effort terminations report failure
plus a `TARGET_UNREACHABLE` escalation. That is a small independent change, and it should go first
rather than wait for the supervisor.

## 4. Two life-saving reflexes, outside the supervisor, on the reflex channel

The discipline that the driver stays passive while idle still holds: an idle driver does not move on
its own initiative. But drowning on arrival and falling to your death are **survival reflexes**, in
the same category as the existing anti-suffocation reflex — on by default, narrowly triggered, no
false positives — and not driver-initiated behaviour.

- **`ArrivalDrownGuard`.** When a non-dive intent arrives with the feet in water in survival, do not
  go passive. Emit an `ARRIVAL_HAZARD` escalation and hold the body at the surface with `swimUp` —
  only at the surface, not ashore, with no XZ movement, since deliberate diving is exempted by the
  dive opt-in, matching the literal `allowsOptIn(DIVE)` gate. The caller decides whether to come
  ashore. Engage only below 50% air supply, so that floating at the surface does not trigger it.
- **`LethalFallBrake`.** The existing `lethalEdgeBrake` covers walking. This adds the unplanned
  airborne case — levitation expiring, being knocked back, leaving the envelope: when `fallDistance`
  is about to become lethal and a water bucket is in hand, reuse the existing
  `allowWaterBucketFall` path. Without a bucket there is nothing to do but escalate; there is no
  magic available.

The open decisions on both are in section 7.

## 5. An amend verb, for changing a running intent in place

`mc.bot.amend {leash?, avoid?, preferY?, dive?, capability?, goal?}`:

- Allows changing the `SearchProfile`'s bias, constraints and capability, and the goal. An intent is
  an immutable value, so amending means constructing a new intent and swapping it into
  `IntentProcess`; the next re-solve cycle, twenty ticks later, picks it up naturally, so the
  existing re-solve machinery gives this away for free.
- Does not allow changing the kind. Turning a `goto` into a `mine` is a cancel plus a new process.
- Returns a `{applied, effectiveIntent}` snapshot. Ship it over RPC first, because the MCP tool
  schemas are frozen at session start, as usual.

## 6. The escalation payload carries its own "why"

```json
{"reason":"CHURN_NET_ZERO", "intent":{kind,goal,constraints…},
 "pose":{pos,yaw,inWater,hp,air}, "threats":[…mc.observe.threats, truncated…],
 "targetDelta":{dist,dy}, "recoveryTried":{churnEscapes,burst,repaths},
 "suggest":["amend leash=32","cancel+escape","skill:bridge-across"]}
```

`suggest` is a cheap engine-side heuristic, and the caller is free to ignore it.

## 7. Open decisions

- **How to break the work up.** The suggested order is: fix the false arrival first, since it is small
  and independently verifiable; then the supervisor with two reasons — churn and arrival hazard — plus
  the escalation channel; then the amend verb; then the remaining reasons and the suggestions. Each
  piece is accepted on replay or live evidence, not on an arena scene passing.
- **Whether `ArrivalDrownGuard` defaults on.** The recommendation is on, following the precedent of
  the other survival reflexes, with a dive intent literally exempt. The objection is that any
  autonomous action while idle violates the passive discipline. If the objection wins, drowning on
  arrival can only be handled by escalating to the caller, and the window before death is roughly 15
  seconds of air supply, so the caller must be online to act.
- **How far `LethalFallBrake` goes.** Escalation only, with no behaviour, versus reusing the water
  bucket reflex. The recommendation is escalation only at first, because the levitation actuator is
  already on a separate actuator backlog and should not be mixed into this work.
- **Where the calibration data comes from.** Replay the existing corpus in `pathArchive` offline — the
  churn cases `r51`, `r60`, `r63` and the rest — to calibrate thresholds, rather than burning live
  runs on it.

## 8. Test strategy

- The supervisor's criteria are a pure function from a snapshot of signals to a reason, so they can be
  tested directly in a scene with no avatar, following the precedent of `clientChatLogSemantics`.
- The escalation channel is tested with a script in the style of `49_events.js`, closing the loop from
  emit to `wait.event`.
- `ArrivalDrownGuard` needs a water-surface arena plus a live comparison: off, the bot drowns; on, it
  holds the surface and the escalation is received.
- The false arrival is tested by changing the assertion in the existing anti-spin reproduction.
- Live evidence takes priority: live and replay results are the truth, and arena scenes are only a
  regression guard derived from them.
