# A surface-first water model — proposed, abandoned, and the readings taken while evaluating it

> **Archived proposal, written 2026-06-17, in Chinese. This design was not adopted and does not
> describe current behaviour.**
>
> The proposal was to stop the pathfinder routing through submerged cells by adding a
> head-submerged predicate, guarding every ground move against it, adding a horizontal swimming
> primitive with a submersion surcharge as the fallback, extending the dive-hold executor to
> follow it, and then retiring the compensation that had been riding the body over submerged
> riverbeds.
>
> It was abandoned two days later. A decisive live diagnosis found that the residual churn was
> heterogeneous and mostly in the **executor**, not the planner — an isolated full search over the
> terrain that was blamed came back clean — so the premise the design rested on was wrong.
>
> A surface-only water model was eventually built, but by a different route and against a
> different argument; that is `docs/design/water-and-swimming.md`, and it is what describes the
> code. The compensation this proposal wanted to retire is still there.
>
> **The reason this file is kept is the appendix.** When the matching implementation plan was
> deleted, the field readings taken while evaluating this design were moved into the end of this
> document rather than destroyed: they were measured on a live client and cannot be recovered
> without running the game again. The appendix carries its own warning that the readings survived
> while the conclusion drawn from them did not, which is exactly how they should be read.
>
> Where the original cited a commit hash, this translation names the change instead. The
> repository's history has been rewritten since, so none of those hashes resolve.

## A unified water-navigation model for a buoyant bot — surface-first routing

**Status**: design, awaiting review
**Date**: 2026-06-17
**Context**: eliminating deep-water pathfinding churn as a whole class, after the ascent guard and
the descent guard had both landed

## 1. The problem

The A* search's `WorldView.canStandAt` (`WorldView.java:264`) returns true for **any** water cell —
the condition ends in `… || isWater(foot)` — so the search freely routes paths into, along and out of
deep water. But the executor drives a **buoyant** body: it can only hold position at the **surface**,
with its feet in the topmost water cell and its head out of the water. It cannot stand on a submerged
riverbed, cannot jump upwards underwater, and cannot hold an underwater position without actively
diving. Every instance of churn in water is the executor failing to follow a path of this kind:

| Direction | Symptom | Status |
|---|---|---|
| Ascending (`stepUp`/diagonal/`swimAshore` from deep water) | cannot push off underwater | forbidden by the ascent guard |
| Descending (`diagDown`/`stepDown` into a submerged cell) | floats above it and oscillates | forbidden by the descent guard |
| Horizontal (`Walk` along the riverbed) | floats up and oscillates | **open, seen at z3034** |
| Waterfall curtains (`stepUp` underwater inside thin flowing water) | churn | **open** |

Until now each move direction has been patched separately. This design attacks **the whole class** by
establishing one invariant, so that the search can only ever emit a water route the executor can
follow.

## 2. Verified facts, established by reading the code

- **F1.** `Walk.valid()` (`Walk.java:12`) is nothing but `canStandAt(to)`, and `canStandAt` is true
  for any water cell. **`Walk` therefore crosses every water cell there is, and both surface swimming
  and underwater horizontal travel depend on it.**
- **F2.** The only horizontal moves are `Walk` and `Diagonal`. The swimming family, `SwimUp` and
  `SwimDown`, is **purely vertical** (`Move.java:315-316`), and the only horizontal swimming move is
  `SwimTraverseBreak` (`Move.java:375`), which breaks blocks. **There is no pure horizontal
  `SwimTraverse` primitive.**
- **F3.** `isWater` is `getFluidState().is(FluidTags.WATER)` (`LevelWorldView.java:65` among others).
  That tag covers **both source and flowing water**, so flowing water — a waterfall curtain — is
  correctly recognised as water.
- **F4, an architectural conflict.** Water crossings are currently routed **deliberately** through
  submerged riverbed nodes, roughly one block below where a buoyant body's feet actually sit, and the
  executor rides the surface over them with `floatOverSubmerged` (`Walker.java:1313`). That
  compensation is **limited to 2.5 blocks below the surface** (`dyNode > -2.5`). The churn at z3034 is
  the search routing nodes to y55–58 while the buoyant feet are at y62 — four to seven blocks down,
  **outside the 2.5-block riding band** — so the compensation never fires, the bot genuinely tries to
  dive, and it sinks and oscillates. That mechanism came out of tuning the open deep-water crossing,
  and this design **retires** it.
- **F5.** The core predicate has to be **head-submerged**. A bot standing on a solid riverbed at y55
  with its head at y56 out of the water is still lifted by buoyancy, and
  `isWater(foot) && isWater(foot+1)` would miss the riverbed case. The correct test is
  **`isWater(foot.above())`**.

## 3. The core invariant: one classification of water level, two derived tests

Classify a `foot` cell by whether the bot can hold position in it:

- `DRY` = `!isWater(foot)`
- `SUBMERGED` = `isWater(foot+1)` — **the head is underwater**, regardless of whether the feet are in
  water or on a solid riverbed, because buoyancy lifts the body either way
- `SURFACE` = `isWater(foot) && !isWater(foot+1)` — feet in water, head out, which is where a buoyant
  body can hold position

and separately, `canPushOff(foot)` = solid ground or dry land beneath the feet (`!isWater(foot-1)`),
meaning the bot can push off to jump or climb. A body floating in water (`isFloatingWater`) cannot.

> **The invariant.** Outside a dive, a buoyant bot's footholds can only be `SURFACE` or `DRY`. A
> `SUBMERGED` cell may be entered only by the swimming family of moves, and only in the context of a
> dive fallback.

Two derived tests, which unify all the scattered existing gates:

- **D1: `headSubmerged(foot) = isWater(foot+1)`.** Any ground-family move whose **destination** is
  head-submerged is invalid. This covers horizontal `Walk` and `Diagonal`, the descending `diagDown`
  and `stepDown`, and any `stepUp` that climbs into deeper water.
- **D2: `!canPushOff(from)`**, meaning the source cell is floating in water. Any **ascending** move —
  `stepUp`, `stepUp2`, `diagUp`, `swimAshore`, `bankClimb` — is invalid, because you cannot push off
  while underwater or afloat.

The riverbed walk at z3034, the descent cases, and the underwater `stepUp` in thin flowing water all
fall under D1. The earlier ascent guard falls under D2.

## 4. The architecture: surface-first routing

### 4.1 Restrict footholds, replacing `canStandAt`'s water semantics

Add `WorldView.headSubmerged(BlockPos foot) = isWater(foot.offset(0,1,0))`.

Each non-diving ground-family move — `Walk`, `Diagonal`, `StepUp`, `StepUp2`, `StepDown`,
`DiagonalAscend`, `DiagonalDescend` — gains a guard inside its own `valid()`: **if
`headSubmerged(to)` is true, the move is invalid.**

- Do not change the global `canStandAt` directly. Its call surface is far too broad — mining, escape,
  stand selection — and that lesson has been learned repeatedly. Derive the restriction at the move
  layer instead, which is the same pattern the ascent and descent guards already use, and fold those
  two into this one rule; see section 4.4.
- The effect is that a non-diving water route **can only advance along the topmost water cells**, where
  the head is in air, and **no submerged crossing node is produced at all**. The churn at z3034, z2744
  and the waterfall cascade all disappear because there is no submerged node to walk on.

### 4.2 A dive fallback layer: the swimming family, plus cost

To keep "surface first, but able to dive when necessary", which is what the user asked for, submerged
cells remain enterable by the **swimming family**, but **at high cost**:

- **Add `SwimTraverse(dx,dz)`**: purely horizontal, no block breaking, registered in `Move.java`, with
  a base cost plus a submersion surcharge. F2 confirms this primitive is missing, and without it
  forbidding `Walk` would leave an underwater tunnel with no legal route at all.
- **Add a unified `pathfinderSubmergedTraverseCost`** to `BotConfig`, starting somewhere around 80–120
  per cell, charged by `SwimTraverse`, `SwimUp` and `SwimDown` on entering a head-submerged cell, and
  **applying to both XZ goals and land goals**. This replaces the split coverage of the current
  `waterCellTax`, which applies to XZ, and `submergedTax`, which applies to land.
- The effect is a layering: when a surface route exists, the ground family takes it; when the only
  route is a capped underwater passage, the search pays the high price and swims through.

### 4.3 The dive-hold executor, following submerged swimming segments

The `Walker` executor must be able to **follow a submerged swimming edge stably** — dive deliberately,
hold depth, and advance horizontally — rather than floating up and oscillating.

- The current `diveHold` (`Walker.java:~2318`) was built for **vertical** `swimDown` only. It needs to
  extend to `SwimTraverse`, meaning horizontal submerged travel: recognise the `swim` move prefix and,
  from the node's Y and XZ, actively pitch down, hold the sink, and move forward until the segment is
  complete.
- This executor only ever works on the **rare** dive-fallback route. The main line is entirely on the
  surface and does not depend on it.

### 4.4 What is retired and what is absorbed, so that there are not two mechanisms fighting

- **Retire** `floatOverSubmerged` (`Walker.java:1289-1317`) and its `WATER_DESCEND_GIVEUP` riverbed
  riding compensation. Under surface-first routing there are no submerged crossing nodes, so the
  compensation has nothing to compensate for. **Remove it in stages** — let the new model take effect
  and verify that open deep-water crossings still work before deleting the old code — so that the two
  never run side by side.
- **Absorb** `isFloatingWater`, `isSubmergedAscent` and `isSubmergedFoot`, which came from the ascent
  guard, into **D2**; and the descent guards on `diagDown` and `stepDown` into **D1**. Both are
  rewritten to derive from the water-level classification in section 3, and the documentation should
  say that they are two projections of one invariant rather than a collection of ad-hoc checks.

## 5. Staged implementation and the verification matrix

> The reproduction rig is `mc.world.snapshot` plus a fixed teleport plus `mc.bot.goto`. **Final
> acceptance is a deterministic end-to-end rerun of `mc.debug.replay replay-0004 replan`**, which the
> user required explicitly. Each stage must keep the existing GameTest suites — 109 and 45 — passing.

- **Phase 0, verify the assumptions in the field**, on a live client after reconnecting.
  - Reproduce z3034 and measure why the search routes to the riverbed rather than near the surface:
    whether `submergedTax` and `waterCellTax` take effect over that stretch, and whether the surface
    is blocked by an overhang.
  - Shallow water, riverbanks and surf: wading with the head out of the water is not forbidden under
    surface-first routing, because the head is in air. Confirm there is no no-path regression.
  - Establish a behavioural baseline for the current `diveHold`, to find the extension point.
  - The output is either a confirmation of the assumptions in section 4 or a correction to them, before
    starting Phase 1.
- **Phase 1, the core head-submerged guard**: the `headSubmerged` predicate plus the seven
  ground-family moves. Verify that every GameTest passes; that replaying z3034 no longer sinks to the
  riverbed, taking the surface or a detour instead; and that the open deep-water crossing arena still
  passes, which is the critical regression guard for the tuning that produced the current crossing
  balance.
- **Phase 2, `SwimTraverse` plus the unified submerged cost.** Verify with a new
  `sealedUnderwaterPassageArena`, whose only exit is a capped underwater passage, that the bot can
  swim through it, and that it does not choose the underwater route when a surface alternative exists.
- **Phase 3, the horizontal dive-hold extension.** Verify end to end on the Phase 2 arena, and that any
  residual submerged segment in a replay is followed smoothly.
- **Phase 4, retire `floatOverSubmerged` and the old riverbed compensation**, removing them once no
  regression is seen. Verify across every arena plus `replay-0004` end to end: arrival, with the churn
  peaks at z2744, z3022 and z3034 all low, and no anomalous window in the video.
- **New GameTest arenas**: `submergedSlotCrossArena` for surface crossing, `sealedUnderwaterPassageArena`
  for the dive fallback, and `waterfallCascadeArena` for thin flowing water not causing churn.

## 6. Risks

- **R1, this rewrites the core of water crossing.** Surface-first routing disturbs the open deep-water
  crossing balance that was arrived at by tuning. Mitigated by making that arena a guard rail from
  Phase 1 onwards, and comparing the open deep-water stretch of the replay at every stage.
- **R2, no-path.** If some terrain's only water route is submerged and neither the swimming family nor
  the dive executor covers it, the result degrades to no path at all. Mitigated by the sealed-passage
  arenas in Phases 2 and 3, and by the fact that submersion is priced rather than forbidden outright,
  so the search can still choose to go under.
- **R3, the search must reliably produce surface routes.** The topmost water cell has to satisfy
  `canStandAt`; this has been checked — feet in water with the head in air gives `canStandAt` true.
  Verified in Phase 1.
- **R4, horizontal dive-hold is new code** and has not been verified live. It is the most fragile part.
  Verified twice over in Phase 3, by its own arena and by replay; and if it does not work, the cost
  layer still keeps the search off underwater routes as far as possible, which is enough for z2744 and
  z3034 since the main line is entirely on the surface.

## 7. Non-goals

- This does not address `stepUp` churn on steep dry mountains, which is a separate dry-staircase
  problem.
- It does not change water-current directional resistance (`directionalCost`) or any other existing
  water cost beyond the ones named here.
- It does not try to make the "underwater is the only passage" case smooth. That case is rare; getting
  through it at all is sufficient, and it is allowed to be slow.

---

## Appendix: the Phase 0 field readings, moved here from the deleted implementation plan

> **Provenance and standing.** What follows was migrated verbatim from the "Phase 0 findings" section
> of `plans/2026-06-17-buoyant-water-navigation.md`. That plan was deleted on 2026-08-27 as a
> self-declared superseded one-off, and the file is no longer part of this repository. These entries,
> however, are **first-hand readings measured in the field**, obtainable again only by running the
> game live, so they were moved into this archive rather than destroyed.
>
> **Read them knowing that the conclusion drawn from them was later reversed.** They were written on
> 2026-06-18, when the reading of the evidence was "D1 is confirmed, proceed directly to Phase 1" —
> the first entry below still says in so many words that the finding *strengthens* D1. The decisive
> live diagnosis on 2026-06-19 overturned exactly that reading, as the banner at the top of this file
> records. **The observations survived; the conclusion did not.** What survives is two observations:
> that an isolated full search at z3034 came back **clean**, so the riverbed sink is an artifact of
> the sliced horizon search and of the executor rather than a preference of the full search; and that
> z2744 **still sank and churned** even with the descent guard already compiled into the running code,
> meaning the targeted fix had not eliminated the class. That second observation is precisely the
> evidence that the churn lives mostly in the executor rather than in the planner. **Do not take the
> "this strengthens D1" line as this repository's present position.**

## Phase 0 findings (executed 2026-06-18, live client on Mountains)

> Environment note: this run followed a full-stack recovery after a system restart. The JDK survived;
> the virtual display, the software GL stack, ffmpeg, the terminal multiplexer and the client launch
> script were all reinstalled or rebuilt. The running client was verified through Gradle reporting
> `:common:classes UP-TO-DATE` and `:fabric:compileJava UP-TO-DATE`, so it **contained the ascent
> guard and the descent guard**; the descent gate at `StepDown.java:18` was present in the compiled
> output.

- **Why the search routed to the riverbed at z3034 — confirmed, with a correction to our
  understanding.** Measured with `mc.observe.map zy@(1763,60,3034)`: the water surface at z3034 is at
  **y62, with the head at y63 in air, which is a legal SURFACE foothold**; the riverbed is at y54; and
  to the east, in +z, there is a **submerged ramp** with feet at y55–60 and the head underwater
  throughout. Running `mc.debug.plan` to the goal from the SURFACE foothold at (1762,62,3032) **and**
  from the submerged foothold at (1763,59,3034) both climbed cleanly to y85 with zero regression and
  without touching the riverbed. **The key correction: an isolated full search does *not* reproduce
  the riverbed sink.** What remains at z3034 is an artifact of the **sliced horizon search plus the
  executor** — the live slice committed a stretch of riverbed nodes — and not a preference of the full
  search. *(As written at the time:)* this in fact strengthens D1, because a hard validity gate
  guarantees that a sliced search can **never** commit a head-submerged node, whereas the present pure
  cost preference cannot guarantee that under slicing.
- **Wading in shallow water with the head in air is not wrongly forbidden — confirmed.** The SURFACE
  foothold at z3034 has its head at y63 in air, so `headSubmerged` is false, D1 does not fire, surface
  `Walk` remains legal, and a route out of the water exists. There is **no no-path risk**. The
  submerged ramp, with the head underwater throughout, is exactly what D1 should forbid.
  `submergedFloorWalkArena` already asserts that `Walk` along a SURFACE row stays valid, which covers
  this regression.
- **Live reproduction under controlled replan of `replay-0004` — the residual class is confirmed
  real.** The replay produced roughly **56 seconds of sinking churn at z2744**, a slot canyon at
  (1704-1710, **57**-62, 2743-2746), sinking to y57 and oscillating back and forth. The video
  narration model flagged the successful window as an anomaly in real time, reporting in Chinese that
  the bot was turning repeatedly left and right in place at the edge of a deep pool without making any
  progress, apparently stuck in pathfinding. **This matters because the running code already contained
  the descent guard**, so the churn at z2744 means that **the targeted descent fix did not eliminate
  this residual class** — or else the remembered claim that an earlier peak had been cleared was too
  optimistic. Either way it is direct support for unifying the whole class under D1 and surface-first
  routing.
- **The `diveHold` baseline — not obtained, deferred to the Phase 3 GameTest.** This run did not drive
  a pure `swimDown` segment separately to establish a `diveHold` baseline. The video pipeline degraded
  partway through into a continuous `Could not open video stream`, which was a clip decoding failure
  rather than a dead endpoint, since there were successful windows proving the endpoint decodes; that
  made close observation of diving impractical. The deterministic `sealedUnderwaterPassageArena`
  GameTest in Phase 3 is the real acceptance gate for the horizontal dive-hold extension, and it does
  not depend on a live `diveHold` baseline.
- **Decision: proceed directly to Phase 1 without changing the section 4 design.** All three core
  assumptions were confirmed: a SURFACE exit exists, D1 hits submerged cells precisely, and the
  residual class is real in live running and not fixed by the targeted change. The Phase 1 to 4 code is
  to be held to deterministic GameTests, with **a live replan replay plus a video pass showing no
  anomalous window as the final acceptance**, run on a freshly compiled client, after restarting the
  whole video stack to fix the clip decoding degradation seen here. One known trap: restart the push
  stream and the consumer cleanly, and never pipe the consumer's output into `head`, because the
  resulting SIGPIPE kills the daemon.
