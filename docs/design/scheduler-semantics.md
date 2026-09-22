# The scheduler: who owns the body this tick

## The problem

The original execution model had one foreground slot: a single process field that a new verb
overwrote. That is enough for "go there" and not enough for anything autonomous. A bot mining
when a zombie arrives needs to fight and then *go back to mining*, which an overwriting slot
cannot express; a boss fight needs a main behaviour with dodging and healing layered on top of
it; and an emergency needs to take the body away from whatever was driving it and give it back
afterwards.

There was one piece of the answer already in the tree and it turned out to be the right piece.
The ambient behaviours under `bot/auto/` — eat, tool, respawn, swim — already ran concurrently
with the foreground process, gated by *channel ownership*: automatic eating only runs when no
process is holding the use key. And the water-bucket fall catch was already an unconditional
early return at the top of the tick, which is a preemption with no name.

## What was decided

### Two mechanisms, split by which channel they contend for

Behaviours that decide **where the body goes** bid for one slot. Behaviours that only produce a
**momentary hand or equipment effect** — raise a shield, eat, drink, swap a tool, replace a
totem, re-armour — run alongside movement under channel ownership, exactly as they already did.

That split is why "walk while holding a shield up" works without a concept for it. Making
everything bid for one slot would have required inventing one.

### Chains bid a number every tick, and the number is a function of the situation

Each movement-channel behaviour is a chain that reports a priority each tick and executes only
if it wins. A priority of zero or less means "not participating", so a chain that has nothing to
do simply does not compete. When a higher bid arrives the incumbent is told it was interrupted
and releases its inputs; when it wins again it is resumed.

Resuming forces a replan rather than continuing the old path. During the interruption the body
may have been knocked back and the terrain may have changed, and reusing a stale path walks into
a wall.

The bands, in `Priorities`, are: panic at 1000, dodging at 900, drowning escape at 500, bunkering
at 300, retreat at 100, urgent shelter at 90, combat at 60, the user task at 50, and idle
shelter-seeking at 40. Reading them in order is reading the design: a reflex outranks deliberation,
and the foreground user task sits near the bottom, above only the behaviour that exists to fill
idle time.

A challenger must beat the incumbent by `Priorities.HYSTERESIS` (5) rather than merely exceed it,
so two chains with near-equal bids do not trade the body every tick.

### A chain that gives up sits out for a cooldown it names

Because a bid is a function of the situation, giving up does not lower it: the situation that made
the chain bid is still there on the next tick, so the chain bids the same band again, runs, gives up
again, and nothing below it ever gets the body. A chain that gives up therefore calls
`ProcessScheduler.bail(chain, reason, cooldownTicks)`. For the next `cooldownTicks` ticks the
scheduler records that chain's bid as 0 and does not ask it for a priority at all, so a debounce
inside the chain starts over instead of running on while it sits out. The scheduler owns the clock;
the chain only names the length. The bail is logged on the `[scheduler]` line beside the handovers,
and while it stands `mc.bot.status` shows it under `chains.<name>.bail` as `{reason, ticksLeft}`.
Cancelling every episode, on `mc.bot.cancel{all}` or on death, lifts every bail, because the site the
chain gave up on is no longer the question.

A chain reaches the scheduler through `Chain.registeredWith`, called once by `register`. A chain built
standalone, as the matrix scenes build them, has no scheduler and its bail is only its own reset.

### A process's status slot is the one its attach switched on

The user-task chain records which `BotState` slots a process switched from off to on during its
`attach`, and switches exactly those off whenever the process ends: completion, cancel, supersede,
an exception, and the cancel that death runs. It does not look the slot up from the process's kind,
because a kind does not name a slot: sleep and replay report into the goto slot, and a lookup by kind
left `goto.active` true after cancelling either one, so status said the bot was walking and the
screen watchdog kept closing containers the player opened. A slot that was already on before the
attach belongs to another owner and is left to that owner. Chains that hold their own process pass
their slot to `ChainProcessLifecycle.drop` explicitly.

## Five things that had to change once the model was real

Running this for a while produced fourteen deaths and several deadlocks, and they reduced to five
structural causes rather than to fourteen bugs. All five were in the semantics of the scheduler, not
in any one behaviour.

**A reflex's state must be cancellable.** The bunker chain kept its anchor in a private field and
re-bid the top priority from it every tick. Because that state was outside any process, nothing
could reach it: `mc.bot.cancel` cancels processes, and this chain held none. The only way out was to
flip a configuration flag, and the state survived death. The fix is a cancellation seam on the chain
interface itself, with an idempotent clear that must leave the chain indistinguishable from one that
had never activated, and a routing layer that invokes it on user cancel and on death.

A related defect in the same chain is worth stating separately because it is a general trap: its
recovery check lived in `tick()`, which only runs when the chain wins the bid. A chain that is losing
cannot notice that it should stop losing.

**A verb must not sell a start as a completion.** Three of the walker's give-up exits reported
arrival, so a caller could not distinguish "arrived" from "stopped trying". The walker now classifies
its exit and names it — the goal was snapped to a standable cell, the best-effort segment was consumed,
the path was consumed, the frontier gave up, churn gave up — and that classification is carried into
the awaitable result. This changes reporting only, deliberately: honesty must not smuggle in a
pathfinding change, or the two effects cannot be told apart afterwards.

**A gate must consume the authoritative signal rather than re-derive it.** The retreat gate built its
own picture of the threat instead of reacting to the damage event, so being hurt by something it did
not classify produced no reaction at all. It became a pure function over a single situation object,
with an unconditional leg for having been hurt. That leg was then narrowed by live evidence: firing at
any health meant a healthy bot fled from one hit, which is its own way to die.

**Shelter-seeking needed a tier that can preempt.** It was priced below any user task, so a bot walking
somewhere at nightfall would never stop to save itself. A second, escalated tier sits above the user
task and combat. That deliberately breaks the original promise that shelter-seeking would never preempt
an active task — the promise was wrong, and the earlier document that made it is in the archive.

**An externally chosen tool needs a grace window.** The idle tool-picker re-selected the best tool every
tick, so a slot chosen from outside could not survive a single tick. Any external write now buys a grace
period during which the picker stands aside.

## What this rules out

There is no queue of user tasks inside the scheduler. One foreground task at a time, with its preemption
and resumption visible in status; sequencing several is the caller's job, and building a plan graph
inside the mod would duplicate it.

The ambient behaviours are not cancellable and do not appear in the priority table. They are turned off
with a setting, not cancelled, because they have no episode to cancel.

## Where to look

- `bot/scheduler/ProcessScheduler.java` — the bid, the hysteresis, the bail cooldown, and the interrupt
  and resume calls. Its JVM test, `ProcessSchedulerTest`, drives it with fake chains.
- `bot/scheduler/Priorities.java` — the bands, each with the reason for its position.
- `bot/scheduler/Chain.java` — the interface, including the episode-cancellation seam.
- `bot/scheduler/CancelRouting.java` — where a cancel is turned into the right chain's clear.
- `bot/scheduler/UserTaskChain.java` — the foreground slot and the forced replan on resume.
- `bot/auto/` — the ambient behaviours and their channel gating.
- `bot/movement/Walker.java` — the exit classification and the terminal report.

The scenes are in `WorldDriverSchedulerScenes`; the two that matter most are the terminal-report matrix
and the retreat-gate matrix, both of which run headless.
