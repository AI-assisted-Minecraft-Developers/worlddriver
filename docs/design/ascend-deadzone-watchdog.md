# The ascent dead zone, and why the fix is a clock rather than a ninth gate

## The problem

A bot that lands short of a cell it is climbing to — roughly 1.2 to 1.5 blocks away
horizontally — sits in a gap between the walker's two step-advance tests. It is too far away
for the arrival test, whose threshold `REACH_DIST_SQ` is 0.45, and too near for the
overshoot re-sync, whose threshold `OVERSHOOT_RESYNC_SQ` is 4.0. A measured stall sat at
about 1.48, squarely between them.

That alone would be recoverable, except that none of the recovery machinery fires either.
The bot is short of the riser rather than pressed against it, so there is no horizontal
collision, and most of the accumulated recovery gates require one. Each gate in that family
was added for one observed posture and each misses this one for its own reason: one needs two
cells of clearance above, two need a horizontal collision, one needs a vertical displacement
greater than three blocks, and the crest patch needs the bot to have already topped out.

And the search cannot help, because it reported the goal as reached — so the futile-search cap
that would otherwise stop a hopeless attempt is blind to this.

The result is an executor that loops indefinitely on one edge with nothing anywhere in the
system willing to say it has failed.

## What was decided

The reading that shaped the fix is that the recovery family is a collection of instantaneous,
mutually blind gates, each defeated by the vertical bob that keeps resetting its progress
counter, and that adding a ninth gate for this posture continues the pathology rather than
ending it.

So the ascent family gets **one bounded-liveness guarantee instead**: an ascent edge either
completes or is declared unreachable within a fixed budget, and that declaration is routed into
the re-route machinery that already exists. What the dead zone never produced was a *trigger*;
supplying the trigger is the whole contribution.

### The machine owns the clock and nothing else

`AscendMovement` is deliberately thin. The legacy drive in the walker remains the single source
of actuation — the delegation branch falls through on every status except the failure one, and the
machine emits no inputs at all. It owns the per-edge episode and the watchdog. Step advancement
stays with the existing advance loop, and the success status is telemetry.

This is narrower than the design it grew out of, which proposed a full state machine owning the
jump timing, the break ordering and the pillar recovery for an ascent. That was rejected while it
was being built, and the class as it exists is the narrower thing.

An episode is a run of consecutive delegated ticks on the same target cell with the same move
name. Changing either — because the walker advanced a step, or a replan swapped the edge — starts
a fresh episode with a fresh clock.

### Progress marks are monotonic high-water marks

The clock resets on progress, and progress is measured against the best height ever reached in
this episode and the smallest horizontal gap ever reached in this episode, each with a small
epsilon against floating-point jitter. Using high-water marks rather than tick-to-tick change is
the whole reason this watchdog survives where the older gates did not: a jump-land-slide-back bob
produces improvement and regression in alternation, and a gate that resets on any improvement never
fires.

An active planned dig counts as progress by definition, because breaking a stone block bare-handed
takes over 150 ticks and a watchdog that could not see digging would kill every legitimate
dig-through ascent.

The budget is three times the step-up freeze window, which at 24 ticks makes 72. A healthy step-up
closes well inside the freeze window, so three times it leaves room for a slow approach without
leaving room for an indefinite one.

### The failure declaration is routed, not handled

Declaring unreachable folds into the existing off-path re-route path rather than starting some new
recovery behaviour. The episode is cleared at the same moment, so a replanned edge onto the same cell
gets a fresh clock — a re-route may legitimately retry the same edge, and it should not inherit a
spent budget.

The obvious objection to arming a watchdog is that repeated failures might compound into a loop that
never resolves. A live replay observed the opposite: **a three-fire re-route loop resolved itself
through the existing blacklist machinery in about fifteen seconds.** Repeated unreachable declarations
self-terminate rather than accumulating, because each one feeds a mechanism that eventually stops
proposing that edge.

### Delegation is one narrow branch

The flag routes only the ascent family — plain step-up, stair-up with breaking, and diagonal ascent.
Water ascents are excluded and keep their own dig recovery; the two-block and parkour ascent variants
are excluded. With the flag off the legacy path is byte-identical, which is what made the comparison
below possible at all.

It has been on by default since 2026-07-20. The evidence was a two-by-two replay comparison over the
ascent-heavy part of the corpus: the only arrival in sixteen case runs was a run with the flag on, and
it had the lowest stall peak of its four; ten watchdog fires all landed on genuine dead-zone postures
with no false trips on a climb that was progressing; and every churn pocket on an enabled run belonged
to a family that already existed with the flag off.

## Reading that evidence

Two cautions apply to anyone repeating that comparison.

Runs with **identical** flags swing by a factor of four to five per archive. Any per-archive difference
smaller than that is noise, which is why the conclusion above rests on the character of the watchdog
fires rather than on the churn numbers.

**Peak stall time is confounded by how a run ended.** A run that fails early and then idles scores as
having a low peak, which reads as a good result and is the opposite of one. Peak stall has to be read
together with the terminal reason, never alone.

## What this rules out

No further per-posture recovery gate should be added to the ascent family. If a new posture stalls, the
watchdog already covers it by construction; if the watchdog is not firing on it, that is a bug in the
progress definition, not a missing gate.

The machine may not actuate. If a future change needs an ascent to be driven differently, that belongs in
the walker's phase classes, not here — otherwise there are two actuation sources for one edge and the
byte-identical fallback is lost.

## Where to look

- `bot/movement/AscendMovement.java` — the episode, the high-water marks, and the clock.
- `bot/movement/Movement.java`, `MovementStatus.java`, `MovementContext.java` — the seam it plugs into.
- `bot/movement/WalkerConstants.java` — `REACH_DIST_SQ`, `OVERSHOOT_RESYNC_SQ`, `STEPUP_FREEZE_TICKS` and
  the watchdog budget derived from it.
- `bot/BotConfig.java` — `walkerAscendMovement`, whose javadoc carries the comparison result.
- `docs/dev/movement-tick-phases.md` — the walker decomposition this sits beside.
