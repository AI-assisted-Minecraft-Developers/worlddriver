# TODO

Open work, and nothing else. Each entry says what is not done and where to look; a few lines is
the budget. Why a change was made belongs in `CHANGELOG.md`, why code is shaped a given way
belongs in a comment beside that code, and what order the work happens in belongs in `ROADMAP.md`.
Investigation records, evidence tables and accounts of how a conclusion was reached do not belong
in any of those, and they do not belong here.

When an item is finished, delete the entry in the same commit that finishes it.

Entries marked **Unverified** have not been confirmed against the current tree; each ends with the
run or measurement that would settle it. Read the code before acting on one.

---

## Porting the tests to StageWright's new path

### The survival ladder has to be rebuilt as new-path tests

Planned in `ROADMAP.md`; it waits on StageWright handing a body to a later test.

### The fixture-authoring tooling has to be redesigned

Planned in `ROADMAP.md`.

### Nothing checks that a test runs each baselined setting at its shipped default

`check_baseline_coverage.py` read settings in a form the code no longer has and was deleted. The remaining guard,
`GameTestBaselineManifestTest`, covers what the baseline sets, not that some test exercises the shipped value.

### Eight stacked doc comments on master

`scripts/check_stacked_javadoc.py` fails on eight places in `bot/control/`, `BotTaskService` and `BotUtil` where
javac keeps only the second of two doc comments.

## Engine and mod code

### A required test fails now and then on a full Fabric with-client run

`MobFightTest.serverBlazeFightStopsWhenTheBodyFallsOut` check C gates a wall-clock pump time
(< 2000 ms) that load inflates. Measure it in something load can't inflate.

### The MCP server's HTTP dispatcher outlives the server

A finished dedicated server's JVM does not end by itself; one holder is the JDK `HTTP-Dispatcher`
thread of `McpServer`'s HttpServer. `McpServer.close()` exists but nothing calls it
(`WorldDriverCommon` only detaches the server). The executor is already daemon; the fix is to stop
the HTTP server at JVM or driver shutdown.

### Out of blocks one course under a two-high bank, the bot goes back into the water

`ClientWaterTest.clientTwoHighBankDigOut` takes 445–1238 ticks; its ashore check wants ≤900 (leg
budget 1200). Standing on its last placed block under the rim, the walk follows the route back down
to the swim node; the search prefers swimming round to digging up from there.

### The R1 route still grazes a few trunks and steps

Last seen touched on R1: birch trunks at 1541,72,-166 and 1470,68,-299, the hanging dark oak log over
the step down at 1389,114,-583, and raised blocks before climbs at 1526,74,-223, 1544,72,-163,
1559,65,-128 and 1563,64,-123. **Unverified:** a fresh R1 run with contact logging would settle it.

### The navigator spends its last node a block short after a detour

`RouteTest.routeReplansWhenTheWayAheadIsWalledOff` ends `GAVE_UP` (`path-consumed`, 1.0 short) and
the bot then slides onto the goal cell; its check C tolerates 1.5 blocks. Look at the step-advance
gates in `bot/control/route/ProgressPhase` for a last node approached at an angle.

### The shore scan gives up in wide water

`AutoSwim.SHORE_SCAN_R` is 10, so in water wider than that the shore direction is null and an idle
bot drifts. Path-driven crossings are fine; this is the fallback when nothing else drives.

### A bot left in water while paused or across a reload can drown

`ClientBot`'s tick returns early when the brain is paused, before any reflex runs. Run the swim
fallback once before that return and after a reload, or refuse to pause in water.

### Jumping off a dripstone cone deadlocks

Standing on a dripstone cone under a jump-up node, `onGround` flickers as the bot slides off the
slope, so the jump never fires and the forward key pushes into the wall. Either price or refuse
stances on dripstone, or treat a flickering foothold under a raised node as stuck and reject the edge.

### `mc.bot.escape` cannot climb out of falling sand

`EscapeProcess` needs a solid non-falling tread, so inside a sand or gravel cap it finds no
direction and bails. `mc.bot.goto`'s break-staircase is the working fallback today.

### A churn give-up reports arrival

The water anti-spin in `SearchPhase` ends best-effort with `TickStep.ARRIVED` /
`PathTrace.Outcome.SUCCESS` and reason `churn-giveup`. It should report a failure the caller can escalate on.

### No chain preempts a task to get food

Only the `AutoEat` reflex exists; nothing interrupts the current task when food runs low and none is
carried. Wanted: a food chain that bids above the user task, like AltoClef's `FoodChain`.

### The learned risk-cost table is off by default with no evidence either way

`RiskCostTable` is wired into the pathfinder behind `riskBias` (default off, scale 15). Turning it
on needs a live A/B with the flag off and on over the same route.

### The build and backfill stance pickers have no reach test

`BuildProcess` and `BackfillProcess` return a stand with no reach test; `BboxFillProcess` gates its
own on `FILL_STAND_REACH`. Adding one changes two placement paths, so it needs its own check.

### A continuation search is discarded and re-issued, and the loop does not terminate

On a fell-off-path stall `StallDetectPhase` calls `Route.abortSearch()`, which drops the continuation
search but keeps `commitEnd`/`pathBestEffort`, the state `RepathPhase` re-triggers on.
`Route.SegmentCommit.reset()` is not a drop-in (it clears `pendingSegment`). Judge a fix on the
repeat count per start-goal pair and `goto` completion.

### The futile-search gate has two measured blind spots

A search that reached the goal but could not be walked is never counted, and the counter clears
whenever the foot moves more than two blocks, so a five-block oscillation resets it every time.
Widen the existing gate rather than adding a second governor — the search phase's own comment says
two governors on one loop race — and reuse the existing cap rather than adding a config knob. Replay
the recorded corpus offline first: a new counting rule has to catch the two known cases without
firing on an ordinary detour around a tree.

### The lava reflex hands control back while the bot is still burning

`LavaProximityEscape` decides it is clear from the environment alone — a nearby threat or being in
lava — and never asks `isOnFire()` or `getRemainingFireTicks()`. Leaving lava does not put a bot
out, so the reflex releases control with the bot still alight. Adding the fire test to that
condition is not by itself the fix: the reflex's action is to walk away from lava, and a burning
bot with no lava left near it does not benefit from walking further.

### Every reflex is off by default

The six reflexes default off by design. A survival driver (now Journeyman) wants them on, healing
before retreat; arming them needs a health-loss distribution, and the in-mod ladder that produced
one is deleted.

### `commandForward` is a strictly weaker channel than `commandMove`

`AvatarInput.tick` lets `commandMove` win over `commandForward`, and the navigator commands a move
every tick, so a same-tick `commandForward(0)` stop from a fallback is dropped. Promoting the channel
changes how fallbacks and processes arbitrate on every topology.

### Two writers of the shared keybinds besides `Body`

Besides `Body`, the shared vanilla keybinds are still written by `BotInteract.releaseKeys` and
`ClientKeybinds`. Only `Body` can drive a server-side player; whether those two move onto it is an
architecture decision.

### A process's `attach()` does not clear the previous run's terminal fields

`ProcessSlot.goalReached/endReason/finalDist` survive `reset()` and only `IntentProcess` and
`BunkerProcess` clear them in `attach()`; `MineProcess` and `ReplayProcess` don't, so status shows the
last run's verdict on a running one. Collect into one `beginRun()`, with a check.

### Default-off flags that no real run has ever turned on

`allowParkour4` is one of them, which means the movement members behind it have never taken a step
in real navigation. Each of them needs either a test that exercises it or deletion. Leaving them as
features awaiting activation is the outcome to avoid.

### Nothing runs the replay corpus's acceptance rule

The harness scraped `[navigator]` log lines and was deleted. `mc.debug.replay`'s result has to carry
the peak stall and arrival itself before a tool can compare a candidate against a baseline again
(`docs/dev/replay-corpus.md`, "The regression check").

### A step-up still jumps in place in water deeper than 0.4

Vanilla swims and rises by bumping into the bank. Fixing it requires `WorldView` to carry fluid
height. **Unverified** whether the behaviour persists: a live run of
`ClientWaterTest.clientShallowPoolStepOut` with its log would settle it.

### The retired water model's predicates and costs are still in the tree

The floating-water predicates and the water-cell, submerged, climb-out and descend taxes are mostly
unreachable with `pathfinderSurfaceWaterNodes` on, except inside dive searches, which keep the old
model. Run both models side by side for a while, then delete one. The design note is
`docs/design/water-and-swimming.md`.

### A bot that climbs out of a trench walks back into it

After reaching the top, the search routes around the trench end with a two-dimensional parkour move,
lands short and falls back in, then climbs again. The parkour landing rule takes no account of the
pillar the bot has just placed or of the trench mouth. **Unverified:** a live reproduction would settle it.

### Digging a bank block by hand while afloat costs about 370 ticks

That matches vanilla's 1/25 floating-and-submerged mining speed. It is open only if bobbing also
resets destroy progress. **Unverified:** measure the progress across one dig in
`ClientWaterTest.clientOneHighBankDigOut`.

### A straightened edge under a leash or bias re-plans every tick

Off-path measures to the edge only on a dry, level, action-free walk edge under an unconstrained
profile. Under a leash or bias it still measures to the far end of the straightened edge, so the bot
re-searches every tick until the last three cells. `WorldDriverTest.entityLeash` and the two
`WaterBankTest.pillarLedger*` tests depend on that cadence. Decouple them before widening the edge reference.

### The deep-water drift brake and the shore-or-swim choice

The deep-water drift brake drops sprint when deep water lies within 1.5 cells along the velocity
(2.5 on a descent). Whether that still decides shore-walk vs swim timing is unmeasured.
**Unverified:** a timed A/B with `navigatorDeepWaterDriftBrake` off and on would settle it.

### Two tools disagree about the day-phase vocabulary and its boundaries

`mc.observe.player`'s time phase and `mc.client.scene.dayPhase` contradict each other in two
windows. Deliberately deferred; the reasoning is in `WorldModel.dayPhase`'s javadoc. Reopen when
the dusk-securing chain's firing window changes.

### Four line samplers share one piece of arithmetic

`losWalkable`, `straightLineBias`, `straightLineDanger` and `isOpenWaterLine` compute the same step
count and interpolation, and `NavigatorInspector.planSpans` and `PreviewSearch.polylineCells` are
near copies. Extract only the per-step cell computation, not an iterator returning a list.
Allocation is expensive by count inside a sliced search's tick budget.

### `activeProcessDetail` is null while the dusk-securing chain owns the bunker

`activeProcessDetail` reads only the foreground user task, so while `DuskSecureChain` runs its own
`BunkerProcess` the status shows `chains.duskSecure.episode = SECURING` but never the bunker's sub-phase.

### Client-avatar actions have no client-thread guard

`ClientPlayerBody`'s `useItemInHand`, `continueDestroy`, hold, slot, container-click,
recipe-placement and attack methods have no client-thread guard, unlike `clientUseItemOn`. No
current caller reaches them off-thread. Add the guard, or an assertion, before anything does.

### The farm process's break timeout is a private constant

Sixty ticks, hard-coded, not wired to the configured break timeout.

### Descend and escape end differently on a shared state slot

Escape resets the slot and logs a bail (debug only) on failure; descend only clears the active flag.
After a descend, the escape slot still reports the old goal, target and start time, and a failed
descend logs nothing at all. The shared slot itself is documented design and is not the defect.
Aligning the two changes what `mc.bot.state` reports, so it needs a check.

### `isFalling(Level, BlockPos)` exists three times, verbatim

In the bunker, descend and escape processes. Do not merge it into the world view's falling-block
check: that one reads through a view which may belong to another dimension. A shared static helper
is safe but buys three lines each.

### Two `fail(...)` helpers take a state parameter they never use

In the craft and smelt processes.

### Reach radii differ by call site

Reach radii are 4.0 (twice), 4.3 (one shared constant), 4.4 and `blockInteractionRange()+0.5`.
`BotUtil.standingEye`'s javadoc tabulates them as deliberate. Reopen only if a live interaction gate
uses a short radius.

### Configuration is global to the JVM

Settings are `public static volatile` fields across eight `config/*Config` classes, global to the
JVM, so two bots, or two tests in parallel, share every setting.

### The freeze window family is not even measured yet

Fluid flags are frozen while the driver is not registered, and no measurement has landed.
**Unverified:** name the flags and the registration involved, then grep the current tests for reads
of `isInWater` and fluid heights to see who is affected.

### The script sandbox assertions guard a filter that is switched off

The five denial checks in `08_sandbox.js` run with `ScriptClassFilter` off. Read the
"08_sandbox mechanism probe" record from a validation run. If `typeof java` is undefined the checks
are vacuous and should be rewritten or removed. Only if it resolves is a `-Dworlddriver.sandbox=on`
run worth taking.

### Every install still needs the Architectury API mod

`fabric.mod.json` and `neoforge.mods.toml` declare `architectury` as a required dependency, for the
fifteen events `WorldDriverEvents` and `WorldDriverClientEvents` subscribe. Replace them with each
loader's own callbacks behind a thin port in `:common`, keeping `block.break` before removal and
`entity.death` at the decision. Fabric API has no block-place event, so that one needs a mixin. The
testmod's registries, `EnvExecutor` and `AvatarTest`'s `PlayerEvent` can stay on it as dev-only.

### The build still goes through Architectury's plugin and transformer

Move to the MultiLoader layout: Fabric Loom for `fabric/`, ModDevGradle for `neoforge/`, MDG's
NeoForm-only mode for `common/`, each loader compiling common's sources instead of the
`namedElements` / `transformProduction*` / `shadowBundle` wiring. Nothing uses `@ExpectPlatform`.
`dev.architectury.loom` is pinned at `1.11-SNAPSHOT` meanwhile; upstream is at 1.17. Do the API item
above first, so no source references `dev.architectury.*` when the build changes.

---

## Bot equivalence

These belong to the bot-equivalence role and are bounded by the parity document.

### The selected-slot section has three residues

Three residues on the server-side bot: a slot change does not stop an item in use
(`ServerPlayerBody.carryTo`), the one-round-trip echo window the equality guard bounds, and a
one-tick lag between a bag-to-hand swap and the client seeing it. Parity doc "Inventory and the held item".

### A furnace menu sixty blocks away is never closed

A real player's tick closes containers; the joined player drifted that far with the menu still open.
`JoinedBody.pump` now runs the player tick, which should close it. **Unverified:** a test that opens a
furnace on a joined bot, moves it away and asserts `containerMenu == inventoryMenu` would settle it.

### Aiming is still split between the two sides

The twin has not landed; the parity doc's "Look" row is `Accepted`. **Unverified:** settles once
someone names which parity row the missing twin belongs to.

### `Inventory.selected` is written in five production places

Three direct (`ClientInput.setHotbarSlot`, `BotInteract.selectHotbar`, `ServerPlayerBody.carryTo`) and
two through `pickSlot` (`HeldItem.holdById`, `BotInteract.creativePick`). The `HeldItem` one runs on
the server bot without `carryTo`'s packet.

### Digging fidelity on the server-side avatar

Bare-handed obsidian breaks, silk touch does not apply, and tools take no durability.

### Pitch and hand swing

Pitch is written directly by about twenty sites outside the look controller; the server-side
place/use/attack actuators do not swing. Parity doc "Breaking, placing and reach".

### Crafting leaves dropped items behind

`CraftProcess` waits 15 ticks in place for a drop and never walks to it. **Unverified** whether that
is a gap: a test that drops the crafted item out of pickup reach would settle it.

---

## Test harness, ledger and instrumentation

### The stacked-javadoc checker cannot see the worse form of the defect

An abandoned documentation block that lands above an undocumented member is not discarded — javac
silently adopts it, and that member now ships someone else's contract. The missing reading is a
mismatch between a block and the declaration it is attached to: a `@param` naming a parameter that
does not exist, a `@return` on a void member. It does not need natural language. The script records
this in its own header.

### The mob cap has no reading

No reading of the mob cap (NaturalSpawner's per-category counts) exists.

### An advancement says not earned while the bag holds the item

The blaze-rod advancement. Measure whether the inventory-changed trigger fires for a joined player
before believing either side of it. **Unverified:** a joined bot picks up a blaze rod and the test
reads `minecraft:nether/obtain_blaze_rod` progress.

---

## Found on the survival ladder

Engine-side defects the deleted survival ladder found; its own strategy-code items went with it.

### The full-budget search tier has a loose clock

A search may run up to the 8 s `CEILING_MS`. Open: whether the widest tier should have a tighter
clock, and why a short segment over open lava expanded tens of thousands of nodes.

### The corridor fire needs a replan trigger, not a surcharge

Decided; not yet built. Pricing the cells next to fire is withdrawn and should not be picked up
again, and so is the per-tick comparison of the bot's cell against the path node, which existed
only to take the surcharge approach apart.

### Two message texts are wrong

A stuck-step annotation attributes the refusal to one of the three booleans before it and omits the
fourth conjunct it printed on the same line. A scene's top-level fallback reason contradicts what
runs actually report. **Unverified:** the exact log text or test name would settle which messages these are.

### An enderman fight sometimes never ends

`MobFightTest.serverEarnsAnEnderPearl`: some fights burn the full 4000 ticks. The scene's
`fights.stall` entry now records each one; read one to tell target-lost from a re-search storm.

### There is no reading for who wrote a cell

Nothing attributes a changed cell to the code that broke or placed it; the only own-break record is
the client-side `ClientIntents.takeOwnBreaks`. A drowning in water a guard had refused to release
went unexplained for want of one.

---

## Source-size budget

`scripts/check_source_budget.py` caps Java files at 3000 non-comment lines and methods at 200 (three
grandfathered `*Phase.run` methods). It passes and no file is near the cap. Run it for current
numbers. Split, do not strip comments. `scripts/check_stacked_javadoc.py` fails on exactly the eight
blocks listed under Porting.

### Two things in this area that must not be tidied

The private stance checks in the build and backfill processes are byte-identical to each other but
stricter than the shared helper — they lack two water clauses, so a waterlogged cell is standable to
the shared version and refused by them. Merging would loosen two placement paths, and wants a
measurement first.

"No Java callers" does not mean dead here. The script class filter is a denylist that is off by
default and does not deny this project's own packages; this Rhino fork removes the `Packages` global,
so a script reaches the public members of any object handed to it rather than any class by name. And
the settings registry, the settings command and the configuration class all reflect on configuration
fields by name. Every candidate from a dead-code sweep has to be re-checked against both before it is removed.

---

## Decided against, with the condition that would reopen it

These are settled. Each line is the decision and the observation that would overturn it. Do not
reopen one without that observation.

- **Unifying the arrival radius across five call sites.** No measured divergence has ever been
  charged to it, and the baseline those radii are measured from is itself still moving. Reopen once
  the ray-casting family settles.
- **Promoting the contact-damage and lava-proximity escapes to a different movement channel.** The
  first five occurrences of the reflex all succeeded; the failure was forty ticks inside a source
  block with less than a block of movement available, which no driver changes. Reopen if a death
  reading ever charges an escape failure to steering or to driver latency.
- **Checking the target cell is empty before placing.** Refusals consume nothing and write nothing;
  the measured ratio is well under one refusal per success, clustered on individual cells. Reopen
  when a run's failure or exhausted budget is charged to repeated refusals.

---

## Leads not yet checked against the current tree

None of these has been confirmed. Before acting on one, grep for the line the code itself would
write, read the production code, and measure the file.

- Escape and descend disagree on their reset conventions.
- A parkour take-off gate: refuse the jump when less than one block of horizontal room remains.
  Planner side done (`Parkour3`'s run-up, `ParkourVoidTest.parkourVoidRunwayGate`); the executor side
  is open — `DrivePhase` only records take-off samples.
- Two of the climb guards cannot reach a cell that is fully submerged.
- An executor leaves its own plan and cannot get back to it — a family, not one case.

### Documentation debt

Tracked in the documentation map's own outstanding list, and belongs to the documentation role rather than here.
