# Escaping a drowning

## The problem

A player that runs out of air dies in a fixed number of ticks, and it dies while some other
process is driving it. Before this reflex existed, the bot drowned under an active `goto`
that kept steering, because the in-process jump backstop in `AutoSwim` shares the input
channel with the process and the process's own per-tick steering suppressed it.

So the fix had to be a scheduling one: drowning under an active process must preempt the
movement channel outright, the way the bunker, retreat and panic reflexes do. That is
`bot/scheduler/DrownEscapeChain`.

Everything after that is arithmetic. Once the reflex holds the channel it has a fixed budget,
and every choice it makes has to fit inside it.

## The budget, and why the threshold is not the knob

Latching at air level `A` with health `H` buys

```
ticks = A + 20 × ceil(H / 2)
```

because vanilla gives a player 300 air, spends one per tick, deals the first drowning damage
when the counter reaches −20, and then deals two points of damage every twenty ticks. At the
default threshold of 100 and full health that is **300 ticks**, which matches a recorded
death exactly: the reflex latched, and the bot died fifteen seconds later.

Digging is priced against that budget, and vanilla divides the dig rate twice, independently.
`Player.getDestroySpeed` multiplies by `SUBMERGED_MINING_SPEED` (0.2 by default) when the eyes
are in water, and divides by five again when the player is off the ground. The worst case is
therefore a twenty-fivefold penalty. For a stone block with a stone pickaxe that is 12 ticks
on dry land, 57 underwater and grounded, and 282 underwater and hovering. Bare-handed stone is
150, 750 and 3750 — which is the hard edge of this whole design: **there is no posture and no
threshold at which a bare-handed bot digs its way out of stone.**

That makes `BotConfig.drownEscapeAirThreshold` (100) the wrong dial to reach for, twice over.
Its range is capped: raising it to 300, so the reflex latches the instant the bot submerges,
lifts the budget from 300 ticks to 500, which still buys exactly one stone block. And the same
number is a divisor in two other places that move the opposite way. `WalkerTickClimb` prices
"can this underwater dig fit inside one breath" as `300 − threshold − 20`, so a threshold of
300 makes that budget negative and the planner loses every underwater-digging edge it has;
`AutoSwim`'s yield floor is also keyed to it, so raising it makes the backstop seize the
channel earlier and cut short digs the walker is in the middle of. The threshold buys an
earlier start, and the failure mode it needs to fix is not starting late but not finishing.
It stays at 100.

## The decision layer

Entry and release live in `bot/auto/DrownEscapeGate`, a pure function with no client types,
so it can be exercised as a headless matrix. The latch arms when the bot is underwater and
air has fallen to `drownEscapeAirThreshold` (100), which is well below the idle float's 240 so
a deliberate dive is not preempted. It releases on wide hysteresis: air back above
`drownEscapeReleaseAir` (280), or the head out of the water with air rising past the threshold
plus `RECOVER_MARGIN` (40).

While latched the chain bids `Priorities.DROWN_ESCAPE` (500). That is above `BUNKER` (300) —
the bunker reflex digs downward, which while drowning is exactly lethal — and above the user
task, and below `DODGE` (900) and `PANIC` (1000), so a creeper blast still wins.

The latch is the episode: it is reachable by `mc.bot.cancel` and by the death hook, and it
holds no process. Cancelling while still underwater and critical re-arms on the next tick by
design; cancelling restores a fresh instance, not immunity.

## The execution layer, and the coverage hole it creates

`tick()` returns immediately when there is no `Minecraft` instance. A dedicated server never
has one, so **a dedicated-server run covers the decision layer and not one line of the
execution layer.** That is structural, not an oversight, and it is why the client-side scenes
below announce the hole in their skip reason rather than skipping quietly.

There are two arms.

**Swim sideways** when the bot's own column is capped but a neighbouring one can surface.
`lateralEscapeScan` walks outward through the cells the player actually fits in and returns the
*first step* of a route it has traversed, not a destination. That distinction was paid for: the
arm used to steer at any column that could surface within five cells whether or not the bot
could get there, and a debug row naming a destination two cells away with stone in between
cannot be told apart from a reachable one. A first step is a cell the scan has already asserted
the player fits in, so a bot stalled on that row is a physics question rather than a routing one.
Deep open water is not capped, so an ordinary dive falls through to the vertical arm.

**Float straight up** otherwise: hold jump, and actively zero every horizontal and turning input
the preempted process may have left pressed. The zeroing has to use `commandMove(0, 0)`, the
channel that outranks the walker's own per-tick command — clearing the direction keys, which is
what this used to do, zeroed nothing at all while a process was running, because the input layer
overwrites the impulses after vanilla's key pass.

## What blocks the rise is measured with the player's hitbox, not with a column

The obstruction is found by `BotInteract.riseBlockedCell`, which lifts the player's **own bounding
box** by `RISE_PROBE` (0.5 of a block) and reports what it hits.

It used to name `p.blockPosition().above(2)` — the cell two above the foot — which is correct only
for a player standing in the middle of its cell. A player box is 0.6 wide, so a player pressed against
a cell boundary carries up to 0.3 of itself into the next column, and one solid cell over there
pins it while that test, and the capped-column scan, and the breathable-neighbour scan all report
a clear path. The scene that reproduces it measured 200 ticks and 0.000 blocks gained with the
scan reporting clear the whole time; the live death it reproduces spent 261 ticks the same way.
Sweeping the player's own box is also right for slabs, stairs and lily pads, none of which a column
scan handles.

Half a block is the probe depth because terminal rise in water is about 0.175 per tick: far enough
to see the face a rising player is about to meet, short enough never to nominate something the
player would have drifted clear of. The arm re-asks every tick.

## Two things the reflex does before reaching for a pickaxe

**Recentre.** When the rise is blocked but the bot's own column is clear all the way to air, the
obstruction is in a neighbour and the fix is at most 0.3 of a block of drift, not a dig — a bot
pressed against a boundary can simply stop pressing, and `commandMove(0, 0)` is what was holding
that pose. The drift is eased by the remaining offset, because a full press across 0.2 of a block
of water carries the bot to the opposite boundary and trades one pinning neighbour for the other.
This bends the arm's no-horizontal contract by less than the lateral arm does: it never leaves the
cell the bot is already in.

**Stand up to dig.** While breaking, if the cell under the feet is not open, the jump is released.
The bot drops onto the floor, `onGround` becomes true, and the same dig costs a fifth. This was
measured on a real client player: one dirt lid cost 380 ticks with the bot held at y=208.235 over
a floor whose top is y=208.0 — 0.235 of a block of hover — and bare-handed dirt is about 15 ticks,
so 15 × 25 = 375. Against a 300-tick budget a 380-tick escape loses and a 76-tick one wins with
room to spare. The walker's own hopelessness gate has priced digs this way all along, on the
grounds that the bot can always ground itself; this reflex was the one place that never did.

The guard on that release is geometry rather than caution, and it has to be. `RISE_PROBE` of 0.5
lifting a 1.8-tall box reaches 2.3 above the feet, so in a two-block-tall pocket a grounded bot
still finds the lid and releasing the jump costs nothing. In a deeper pocket the bot would sink
away from the lid, `riseBlockedCell` would return null, the jump would go straight back on, and
the two would oscillate with the break progress reset on every cycle — so the release is
conditional on the cell under the feet being standable.

## Instrumentation is unconditional

Both arms print a throttled row every ten ticks, and neither is gated on
`BotConfig.walkerDebug`. That is a deliberate reversal. The rows used to be behind that flag,
and gate runs never set it, so a run in which this chain latched and the bot drowned 300 ticks
later at an unchanged position produced exactly one line of log — the latch — and nothing to say
which arm had it. A reading available only behind a flag is a reading the run that needs it never
takes, and the zero it leaves cannot be told apart from the arm never having run.

The volume is bounded by the episode rather than being a stream: this only ticks while a bot is
drowning, so it is about ten rows per near-death. The two arms keep separate throttle counters,
because one counter shared across two mutually exclusive arms lets a run of lateral ticks advance
the vertical arm's phase, landing its rows on an arbitrary subset of ticks. The one exception is
the row printed when the lateral scan found a breathable column it could not reach: that reads the
vertical counter without incrementing it, so the two halves of the same decision land on the same
ticks.

## Why the client calls go through `BotInteract`

`riseBlockedCell` and the debug row both take a `LocalPlayer`. Declaring a method with
`LocalPlayer` in its descriptor **on this chain** stops the chain loading on a dedicated server —
and the gate's matrix scenes construct it there. `invokestatic` resolves its owner, not its owner's
dependencies, and `tick()` returns at a null `Minecraft` before anything in `BotInteract` is ever
reached, so the helper class is simply never loaded. `continueDestroy` is in the same position for
the same reason.

The discriminator here is widening, not calling: handing a client type to a parameter declared as a
wider type forces the verifier to load the client type to prove the subtype relation. Holding one in
a local variable and calling its own methods has always been fine. `AGENTS.md` states this as a hard
rule; it is repeated here because this chain is the class that established it.

## What is still open

The reflex is best-effort against a sealed stone lid and cannot be made better by tuning. Bare-handed
it cannot dig out at all, which is why the scene that exercises the lid break equips a pickaxe: a
scene that armed this arm bare-handed would be asserting a privilege vanilla does not grant.

`wd.drownEscapeClientPinnedByNeighbourColumn` is registered as an optional failure rather than a
required one, because it is a sensor for a hypothesis that is not settled rather than a verdict. When
the hypothesis is settled it should be promoted in the same change, or it becomes a red nobody reads.

## Where to look

- `bot/scheduler/DrownEscapeChain.java` — both arms, the recentre, and the stand-up-to-dig release.
- `bot/auto/DrownEscapeGate.java` — the pure latch, and the only part a dedicated server exercises.
- `bot/util/BotInteract.java` — `riseBlockedCell`, `drownVerticalRow`, `continueDestroy`.
- `bot/BotConfig.java` — `autoDrownEscape`, `drownEscapeAirThreshold`, `drownEscapeReleaseAir`.
- `bot/scheduler/Priorities.java` — where 500 sits relative to the other reflexes.

The scenes are `wd.drownEscapeGateMatrix`, `wd.drownEscapePreempt` and `wd.drownEscapeSurface` for the
decision layer, which run anywhere, and the five `wd.drownEscapeClient*` scenes for the execution
layer, which execute only on a topology with a real client.
