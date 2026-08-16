# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **A rehearsal can now flood a shaft on purpose, and rung 11 can be rehearsed at all.**
  `-Prehearse=OBSIDIAN` stages the rung's own starting conditions (an empty bucket, two stone
  pickaxes, the body a few blocks from the lava column at the surface — `JourneyRoute.firstLava` is
  already baked, so no survey is paid for), and `-PwetShaft=true` floods a lens around the descent
  once it is four blocks down. Same two locks as `breakAStair`: off unless asked for, refused when
  no rung is being rehearsed, and counted into `JourneyLedger.staged`.

  It exists because the failure it reproduces is random. The obsidian rung's descent floods on some
  climbs and not others — the same seed and the same column `-4,56` read `below=dirt` on one ladder
  run and `below=water` on the next — so a remedy for it could only be verified by being unlucky, at
  twenty-five minutes a try.

  **Sizing the lens is itself the finding.** Three cells in one column did nothing; three wide and
  four deep did nothing either. A body in water SINKS, and the descent's settle is 60 ticks, which
  is long enough to fall nine blocks: with a four-deep pocket the run recorded `shaft.4 = -4,59,56`
  and then `shaft.5 = -4,50,56 below=-4,49,56 stone` — the body crossed the whole pocket inside one
  leg and came to rest on its dry floor, so the pass that followed saw a solid support and the
  guard's first condition was never met. Twenty deep is what holds the body in the state the guard
  is written for, and it is also why the natural failure is random: the same race, decided by where
  the groundwater's floor happens to sit.
- **Rung 12 re-reads its frame after every step that can move a block, so a cell that stops being
  obsidian names the instruction that took it.** The rehearsal of 2026-08-15 recorded `CONSUME` for
  all ten casts and not one `cast.missed.*` — every cell WAS obsidian at the instant it was poured —
  and then finished `frame.cast=6/10`. Four cells disappeared *after* being cast, and the only
  reading that existed was the final count, which can date a loss to "somewhere in ten round trips"
  and no closer. Only one of the four had left any trace at all.

  `auditFrame` checks every cell already cast after each of the two reopens, the tidy, the water
  pour, the climb up, the load, the climb down, the pre-pour reopen, the recover and the drain, and
  reports the first loss ONCE as `frame.lost.N` — naming the step it vanished inside, the last step
  it was still whole after, and where the body was standing with what in its hand. `frame.cast`
  now carries both numbers (`10/10（浇成过 10 格，浇成之后又丢了 0 格）`), because "four are
  missing" and "four never cast" want opposite work.

  It named a mechanism on its first reproduction, a single-bucket rehearsal that cast all ten and
  finished 9/10:

  ```
  frame.lost.1 = -9,60,38 浇成黑曜石之后又没了：现在是 air，丢在「wet.9 挖开水位格 -10,61,38」
                 这一步里（上一次它还在，是「cell.9 挖开门框格 -10,60,38」之后）；
                 身体 -10,57,38 距 3.2 格，手上 minecraft:cobblestone；已浇 9 格，现存 8 格
  ```

  The step that lost it is a dig of the NOTCH, the cell it cost is the top-left ring cell two rows
  below it, and `-10,57,38` is not a corridor cell at all — it is an interior cell of the portal's
  own doorway.

### Fixed
- **The shaft's「这根柱子不干燥，换一根」now actually changes columns.** It printed that sentence and
  then called `ctx.fail` for as long as it existed — a diagnostic that names a remedy nothing
  performs, which is worse than one that names nothing, because it ends the search. Two ladder runs
  died on that row before anyone checked whether anything ever swapped.

  `descendByMining` takes an `onWetColumn` callback; rung 11 wires it to climb out, ban the drowned
  column and sink the shaft somewhere else, twice at most. The ban list is needed on both halves of
  the choice: `whyNotDiggable` deliberately does not look at the MIDDLE of a column (requiring the
  whole thing dry rejected 280 candidates out of 280 around this seed's pool — that is what an
  aquifer is), so without it `pickDigColumn` rings outward from the same centre and returns the same
  column, and `stepOntoDiggableColumn` re-adopts it as「就近合格柱」.

  Rungs whose column is a surveyed constant pass no callback, and their failure message no longer
  names a swap they cannot make.

  Verified point-blank rather than by climbing (`-Prehearse=OBSIDIAN -PwetShaft=true`):

  ```
  shaft.sabotage   = -4, 59, 56 周围 3×3、y=38..60 共 207 格灌成水了
  shaft.reColumn.1 = -4, 47, 56 这一柱中段有水，身体浮起来了（脚下 water）—— 爬回 y=63 换第 2 根柱子重挖
  shaft.column     = -10,51 (岩浆柱偏 4 格)     shaft.landedY = 27     cast.cellAfter = obsidian
  ```
- **A climb's drift correction asks a question the walker can answer, and asks it more than once.**
  Three separate defects in one branch, all found in one rehearsal of rung 12's cell eight:

  1. It walked to `Goal.Block(climbColX, at.getY(), climbColZ)` — the cell level with the body,
     which is only the right cell on flat ground. The portal rung's raise pins a corridor column
     inside a HOLLOW alcove, where that cell is air over air and no route to it exists. The
     correction now aims at the highest cell in the column a body could actually stand in.
  2. That goal is a `Goal.Block`, not a `Goal.XZ`. `Goal.XZ` reports `ignoresY`, and the
     pathfinder's own contract applies its descend-tax to exactly those goals — while here
     descending IS the move.
  3. It took ONE attempt. The walker's own verdict was `end=path-consumed err=null（想去 -9,56,37，
     停在 -9,57,38）`: not "no route" but a partial path walked and reported done, which is
     `wd.serverWalkerArrivedShort` and which everywhere else in this suite is answered by asking
     again from where the body now is. Three attempts, and a leg that moved the body zero cells ends
     the retry immediately (`driftWedged`) rather than asking an identical question a third time.

  It also stops the correction breaking blocks: the casting phase runs with `allowBreak` on, and a
  reposition that mines is how a cast frame cell gets eaten (`frame.lost.1`, twice). Walking inside
  a room the rung just hollowed out needs no digging.

  The row this was hiding behind: `recover8.rise.raisedY=58/60（停在 -9,38，指定柱 -9,37）` read as
  a raise that fell short. It was a raise that **never placed a single block** — the correction
  failed on course zero and `pinnedLost` ended the climb before the tower ran once.
- **A bucket now aims from where the body is when it uses the bucket, not from where it was two
  ticks earlier.** `Avatar.aimAtBlock` stores a yaw/pitch computed from the current eye; everything
  that fires the ray — `aimedAt` for the prediction, `Item.getPlayerPOVHitResult` inside
  `BucketItem.use` — re-derives a direction from those angles and starts it at the LIVE eye. `scoop`
  aimed and THEN settled two ticks, so any fall in between left the fill firing a ray computed for
  a position the body had left, and neither reading could show it: both print a cell, and a cell is
  a metre wide.

  The order is now `settle(2) → aimAtBlock → predict → use`, all three of the last steps in one
  tick, at no extra tick cost. `topUpBuckets` was reordered the same way; the comment there
  justifying its two ticks with `pick()` was wrong — the bucket never goes through `pick()`, so an
  aim and a use in the same tick see exactly the same rotation.

  Measured as an A/B on identical geometry. The single-bucket rehearsal that had failed twice at the
  same cell reproduced its own starting state word for word — body `-10,58,35`, eye
  `-9.38/60.16/35.64`, the same 0.54-block drop over the settle — and passed:

  ```
  recover9.aimsAt = -10,61,38 Block{minecraft:water} 源块=true 液位=8；眼睛 -9.40/59.62/35.61
                    朝 yaw=1.96 pitch=-33.07；settle 这两 tick 里眼睛挪了 0.54 格（y 60.16→59.62）
  recover9.result = CONSUME
  ```

  Pitch −25.14 → −33.07 (hand-checked as −33.03; the rest is float quantisation), and the ray now
  passes over the obsidian it used to stop on. Four rehearsals on the fix — two single-bucket
  passes at `frame.cast=10/10`/`portal.cells=6/6`, one single-bucket and one four-bucket failure
  elsewhere — landed all twenty fills as `CONSUME` with no `frame.lost.*`, `frameOnLine` or
  `frameStuck` at all.

  `.aimsAt` also carries the settle drift now, so「was there anything to neutralise this run」is
  read rather than assumed.
- **Rung 12's mould digs no longer let the WALK to a cell break anything.** `ServerWorldDriver.mine`
  is a walker goal plus a swing, and the walker plans with `BotConfig.allowBreak` on for the whole
  casting phase — so a cell with no walkable approach gets one dug THROUGH the mould. `standBehind`
  only covered the case where a corridor stand exists; when it reported `.noStand` the dig ran
  anyway, and the route it then took was the one nothing was watching.

  The reopens now go through `digWithoutTunnelling`. That does not disarm the dig: `allowBreak`
  prices the WALK's breaks (`LevelWorldView.breakCost` → infinity) while the target is still broken
  by `avatar.breakHold` once navigation stops, gated only by reach and exposure. A cell with an
  approach still opens; one without now reports `.stillShut` / `dig.*` instead of quietly paying for
  itself with a cast cell.

  **Where this leaves the rung.** One four-bucket rehearsal reached `frame.cast=10/10（…又丢了 0
  格）` with `portal.cells=6/6`; three single-bucket ones are red — one on a gravel column plugging
  the corridor cell a bottom-row dig has to stand in (upstream of all of this), one at 9/10 (the
  `frame.lost.1` above, now fixed), one at `recover9` unable to fill. One run is not a pass rate.
- **Rung 12 puts the eye back on the row the water was poured from before it goes to take the water
  back.** A cast pours water into the interior/notch cell from a row that was verified for it, then
  fetches lava and pours THAT into the frame cell below — and the pour's own walk is free to drop
  the body to whatever cell has a floor, which in a hollow alcove is seven rows down. From there the
  line to the water runs straight through the obsidian that was just cast between them, which is the
  geometry the guard above now refuses to dig its way out of. `riseToTakeItBack` is the other half:
  the same `SOURCE_ONLY` clip the bucket runs is asked first, so on every cell whose recover already
  works it is a no-op and cannot perturb it.

  **It is still not running, and the reading added alongside it says why — measured, not inferred.**
  `eyeNow` prints the continuous eye on both sides of the question, and a single-bucket rehearsal
  answered it: `recover9.fromHere … 眼睛 -9.38/60.16/35.64` and, an instant later,
  `recover9.aimsAt … 眼睛 -9.40/59.62/35.61`. The eye fell 0.54 of a block between the two, so this
  is the BODY MOVING and not float quantisation (which is a 1e-5 effect). The pitch to the target
  centre is −25.09 from the earlier eye and −33.03 from the later one; the stored rotation is
  −25.14, and fired from the later eye it reaches y=60.98 at the frame plane — inside the obsidian
  the aim then reported. `60.16 − 1.62 = 58.54` is not a block floor, so the body was mid-fall when
  it was asked; the two ticks `scoop` must settle (because `pick()` traces from the previous tick's
  rotation) are the two ticks it lands in. `aimAtBlock` stores an ANGLE, not a target, so the angle
  expires the moment the body moves — the third instance of this repo's ray-timing family, and it
  means the fix is a matter of WHEN the question is asked, not which ray answers it.

  **The first version of it did nothing at all, and the reason is this repo's own fourth question
  about a diagnostic.** It raised through `standLevelWith`, whose gate is `standToPour` — so a
  question about a SCOOP was answered by whether a POUR spot exists, the gate said yes, and the run
  printed `recover8.rise = 看不见 -9,61,38 里的水` above a body that never moved (no `.raise`, no
  `.raisedY`). The raise is now `raiseTo(..., pouring=false)` and the column is verified by
  `scoopSeesFrom` (`SOURCE_ONLY`, onto the water itself) rather than by `pourLandsFrom`
  (`Fluid.NONE`, onto the backing or floor). The two disagree exactly where it matters: over a
  freshly cast cell the pour question passes and the scoop question does not.
- **Rung 12's bucket no longer answers a blocked sightline by mining the frame it is casting.** The
  clear-line branch in `JourneyFill.scoop` breaks whatever the ray stops on, and down in the alcove
  the only thing tall enough to block one is the mould itself — measured, `recover9.clearedLine.3 =
  -10,60,38 Block{minecraft:obsidian} 挡在眼睛和 -10,61,38 之间，敲掉它`. **It really did break
  it**: this body's `ServerPlayerAvatar.breakHold` goes to `Level#destroyBlock`, which has no
  tool-level gate at all (its own javadoc says this avatar "harvests obsidian with its fists"), and
  the journey never sets `faithfulBreak` — so a stone pickaxe takes obsidian here in one swing.

  Refused by COORDINATE (`isFrameCell`, the ten ring cells this rung computed itself) rather than by
  block id, which would also protect unrelated obsidian and would stop protecting a cell the moment
  something else got into it. The blocked fill answers by walking to a stand the clip verifies
  (`.stepOut`) or, when there is none, spending the attempt with its full geometry (`.frameStuck`)
  instead of recursing on an unchanged question. Observed doing exactly that:
  `recover9.frameOnLine.3 … 但它是门框格 —— 不敲` and `recover9.frameStuck.3`, with no
  `frame.lost.*` anywhere in the run — the mould survived and the failure landed on the fill, with
  its geometry, instead of silently on the count.

### Changed
- **Rung 12 climbs the staircase only when the bag has no lava left, so the ten round trips become
  `ceil(10 / buckets)`.** The pour is cheap and the commute is where the rung dies — falling into the
  pit the fill itself left in the lake, losing the way back to the stairwell mouth, water in the
  doorway. Ten cells each did their own `goUpToThePool → fillFrom → returnToTheForge`; now
  `castOpenedCell` pours straight from the bag when it can, and `loadBuckets` fills every empty
  bucket in one visit (keeping one empty, because taking the water back needs one and a pour that
  MISSED does not free the lava bucket — without that reserve the recover comes back dry and the next
  cell reports 「开浇前手上没有水桶」 for a failure one cell upstream).

  **This needed nothing to land with it.** With the one bucket the ladder's iron currently buys
  (`IRON_INGOTS_THE_KIT_COSTS = 4` = bucket 3 + flint-and-steel 1), the top-up loop stops before its
  first iteration and the rung walks the same ten trips it always walked. Measured both ways on
  rehearsals of the same rung:

  ```
  -Prehearse=PORTAL_LIT              bucket.before=0  lava0..lava9.loaded = 1 桶  →  10 趟, REACHED 10/10
  -Prehearse=PORTAL_LIT -Pbuckets=4  bucket.before=3  lava0.loaded = 3 桶（空桶只剩 1 个，留着收水）
                                     lava1.fromBag = 2 桶岩浆还在包里 —— 这一格不上楼
                                     lava2.fromBag = 1 桶岩浆还在包里 —— 这一格不上楼
                                     lava3.loaded = 3 桶  →  2 趟浇了 4 格（旧代码要 4 趟）
  ```

  The first bucket still goes through the whole of `fillFrom` and still fails the rung when it comes
  back empty; every bucket after it is attempted only when there is an empty bucket AND
  `visibleSourceNear` already has a source in view, and the first attempt that does not take ends the
  loading without a verdict. Coming home with two when three were possible costs one trip; failing
  over it would cost the run.

  The multi-bucket branch is unreachable on a real climb, so `-Pbuckets=N`
  (`JourneyRehearsal.stagedBuckets`) exists to execute it — rehearsal-only, defaulting to 1, and
  counted into `JourneyLedger.staged` like `breakAStair` and `forgeAway`. Judge it by the trip count,
  not the colour.

### Added
- **A flight recorder for the nether crossing, because every reading of it was a photograph of the
  wreckage.** Rungs 14 and 15 both die the same way and every piece of evidence either had was taken
  after the leg gave up — `fortress.around.1 = 脚下=cave_air 身处=cave_air 头顶=air`, then two
  attempts of `六面全是 lava`. Three completely different bugs print that same line: a floor that
  stopped being a floor, a body that walked off one, and a leg judged finished while the body was
  already in the air. `JourneyFlight` watches the body on every tick of a walk instead — the wait's
  predicate is the only code that runs that often — and records the transition rather than the
  outcome. Three rehearsals of rung 15 separated the three in one round:

  ```
  warped.fell.1.0 = #1 t=56 从 15,41,3 走出了支撑格（上一 tick 踩着 [15,40,3=netherrack]，
                    这一 tick 脚下是 [15,41,3=air 16,41,3=air]）→ 落进岩浆 18,30,0，坠 11 格
                    （最快一 tick 掉 1.19 格），已走 8/272 格，计划第 1/8 步
  warped.flight.1 = 收工那一刻：goalReached=false end=failed:no path (expanded=1) …泡在岩浆里
  ```

  The support block is still there — its POSITIONS are kept from the previous tick and re-read after
  the fall starts, which is what makes "removed" and "walked off" separable — and the walk's own
  verdict arrives 320 ticks later, so the lava is upstream of `expanded=1` rather than downstream of
  it. It happens 8 to 32 blocks into a 272-block leg, not at the 105 blocks a rung-14 log suggested.

  Two things it got wrong first and now does not: it read the single cell under
  `blockPosition()`, which for a 0.6-wide body is frequently not its support, and printed
  `原地离地（脚下 air）` — a line with two causes and no way to tell them apart. It now reads the
  whole bounding-box footprint. And it kept the run-up until the fall LANDED, by which time an
  eleven-block drop had rolled every pre-fall tick out of the ring; it is snapshotted at launch.

### Fixed
- **A fill asked whether the bag held a bucket, not whether THIS use had filled one.** `scoop`'s
  success test was `rig.carrying(id) >= 1`, which is the same claim as "the fill worked" only while
  the body can carry exactly one — and it could, so the two were indistinguishable and the weaker one
  shipped. Carry two and the second fill passes before it is attempted: the first bucket is already
  in the bag, so the test is true whatever `useItemInHand` did, and a fill that missed reports success
  and walks a bucket short to a pour that reports 「浇不出黑曜石」. It now measures the delta, which
  cannot be fooled at any bucket count, and the miss line says `这一次没装上（minecraft:lava_bucket
  1→1）` instead of the now-wrong 「桶里还是空的」. `scoopWater` had the same shape and got the same
  fix; there the short-circuit above it means the count was always 0, so it was correct today and
  would have stopped being correct silently.
- **`fp.fallDistance` is structurally always 0 on this body, so the guard built on it never once
  fired.** `ServerPlayer.checkFallDamage` — the override `Entity.move()` calls — is an empty method
  in 1.21.1; the accumulating one is `doCheckFallDamage`, reached only from the movement-packet path,
  and a FakePlayer has no connection. Measured three times: `最快一 tick 掉 1.14~1.19 格` against a
  `fallDistance` of `0.0` for the same fall. Two consequences, both now corrected. The nether rungs'
  `hazardBlockingARetry` refused a retry for a body "still falling" via
  `!onGround && fallDistance > 2.0f`, which is unreachable — only its lava and water branches ever
  worked, and the note claiming otherwise was wrong. And `surroundings` printed `坠=0.0` about a body
  in free fall, which is the worst kind of evidence row: one that ends an investigation with a
  confident wrong answer. Both now read the body's own vertical velocity, which survives having no
  client.
- **The staircase audit asked three of the four questions the flight is cut for, and certified a
  flight the body could not climb.** `digStairsDown` cuts THREE cells per step and its javadoc says
  why the third exists: "going back UP, the body jumps from a step to the one behind it, and a jump
  needs clearance two above the feet it starts from." The audit written later never asked about that
  cell. The ladder run of 2026-08-15 died on the very first ascent — no cast at all — with
  `走不上楼梯：停在 -9, 56, 36 … 楼梯自检：16 级都完好`, and `-9,56,36` is `stairs.bottom`: the body was
  standing exactly on the bottom step, on a flight the audit had just called perfect.

  Read out of that run's saved world, the bottom step's clearance cell `-9,58,36` holds **dirt** —
  the body's own pillar, part of a whole y=58 slab of it across the alcove mouth (`-10,58,36`,
  `-9,58,36`, `-8,58,36`, `-9,58,37` all dirt), placed while `MineProcess` reached the frame's upper
  rows. `StepUp.valid` refuses a +1 step unless `from.above(2)` is passable, and every flight leg
  walks under `NoBreak`, so `StairUpBreak` — the variant that would have broken through it — was not
  available to route around. A* therefore had no legal move upward out of the bottom step, and the
  four remaining waypoints each burned their 600-tick settle against a question with no answer.

  The audit now reads the clearance cell too, for every step except the top one (nothing is ever
  climbed FROM the top cell, and it is also the one cell of the flight the dig never cut — asking
  about it would report untouched surface rock as a broken stair). A blocked clearance is mended the
  same way a blocked head is: with the pick, at arm's length. `stairs.asCut` and every
  `<tag>.stairsBroken` now distinguish it in words — `起跳格` rather than `挡住`.

  **Measured on the next real climb.** Six ascents, every one of them ending exactly on the
  staircase's top cell — `lava0…lava5.upEnded = -9, 66, 21（楼梯顶 -9, 66, 21）` — and not one leg
  of any ascent fell short, so `upStopped` never fired. Six cells cast (`cast0…cast5.result =
  CONSUME`) against zero on the run before, with `staging.calls=0` and rungs 13–20 correctly
  BLOCKED. The rung now dies further along, on a different bug: `第 7 格没挖开就要浇：-8, 59,
  37=Block{minecraft:granite}`, with `forge.carved = 53/67 格开了，14 格没挖动`. That is the alcove
  carve, not the staircase. One run is not a rate — 6 of 10 is not "stable".

- **`N 级都完好` could not tell a dry staircase from a drowned one.** Every question the audit asks
  is `blocksMotion()`, which is false for a water block, so a flooded flight reports as perfect. In
  the same run the bottom step's foot cell was `water` — the cast's own pour at `-9,57,38` draining
  back through the corridor — while the line said `16 级都完好`. That line is the one the last three
  rounds of work on this rung quoted to rule the staircase out; it was wrong twice over on the run
  that produced it. `stairReport` now appends `N 格泡在流体里：<cells>` when any step's foot or head
  holds a fluid. Deliberately NOT a fault: a pick does not mend water, and the alcove's drainage is
  its own open item — the point is only that the sentence can no longer be read as "dry". It fired
  on the next real climb at the cell it was written for: `cast2.stairsBroken = 1/15 级坏了：-9,66,21
  脚下 -9,65,21=air；2 格泡在流体里：-9,56,34=water，-9,56,35=water`, where `-9,56,35` is that run's
  `stairs.bottom`.

### Changed
- **A flight leg now says where the body actually stopped, not just how high it got.** Both ends of
  the staircase read only the finishing height, so `走不上楼梯：停在 …` read identically whether the
  body never left the alcove or climbed four fifths of the flight and stalled. `walkTheStairs` now
  records the FIRST waypoint it did not reach, with the leg index, the cell asked for, the cell
  reached, the distance between them, and the four cells `StepUp.valid` reads about wherever it
  stopped — what holds it up, what it is standing in, its head room, and the cell it must jump
  through. Surfaces as `<tag>.upStopped` / `<tag>.returnStopped` and in both failure messages.

  It earned itself twice on its first climb, with two readings the old message would have printed
  identically. `cast2.returnStopped = 第 0/4 段：想到 -9,66,21，停在 -9,63,19，差 3.61 格 —— 脚下
  stone，身处 lava，头顶 cave_air，起跳格 -9,65,19=stone（挡着，跳不起来）` — a body submerged in
  lava under a stone ceiling, which the pillar-out recovery then rescued. `cast5.returnStopped =
  第 1/4 段：想到 -9,62,25，停在 -9,66,21，差 5.66 格 —— 脚下 cobblestone，身处 air，头顶 air，
  起跳格 -9,68,21=air` — a body standing at the stairwell mouth with all four cells clear, which is
  a different finding entirely and still open.
- The flight's audit, repair and rehearsal sabotage moved out of `JourneyPortalRung` into
  `JourneyStairs`, which put that file back under the 3000-line source budget. Mechanical move; the
  seam is that the rung cuts and walks the flight while `JourneyStairs` asks whether it is still one.
- **The dig sealed the very cell it had to stand in, and only cobblestone was ever swept up.**
  `MineProcess` reaches a frame cell above head height by pillaring, and it pillars with
  `JourneyShaft.pillarBlock` — whichever of seven spoils the body carries **most** of. Every
  rehearsal is handed `cobblestone×64`, so for thirty runs that was cobblestone and `tidyTheAlcove`
  took it away. A real climb arrives with what eleven rungs left behind: the ladder run of
  2026-08-15 arrived holding **dirt**, and the first frame cell then read
  `dig.cell.0 … canBreak=false … west=Block{minecraft:dirt}(实心)` beside
  `cell.0.noStand = … 7,56,19 被 Block{minecraft:dirt} 占着` — at floor level, in a chamber cut
  through granite, where dirt is not terrain. `7,56,19` is not in `carve.stuck`; it had been carved
  open and then filled by the rung's own pillar. Three retries re-asked the unchanged question and
  the rung died five casts' worth of wall clock later, at a pour.

  A stand now takes back the **one** cell that blocks it — feet or head, either candidate stand —
  identified against the carve's own stuck list rather than by block id, and bounded at two clears.
  Observed doing exactly that on the next rehearsal, at the cell the `noStand` rows had been naming
  for three runs: `cell.0.litter.2 = -9,57,37=Block{minecraft:cobblestone} 挖门框时自己垒进落脚格的`,
  and again at `cell.2.litter.2 = -8,56,37=Block{minecraft:gravel}` — a block no id list would have
  had on it.

  **Confirmed on a real climb.** The next ladder run took the same cell — `cell.0.litter.2 =
  -9,57,37=Block{minecraft:cobblestone}` — cast cell 0, and went on to cast **all ten**:
  `frame.cast=10/10`, `frame.obsidian=10/10`, the first time the ladder has ever filled the mould.
  Eleven rungs climbed, `staging.calls=0`, rungs 13–20 correctly BLOCKED. It died one step later, on
  the doorway, which is a different bug and is below.

  **One cell, not a sweep, and that restriction is measured.** The obvious wider fix — clear every
  corridor cell that is solid and that the carve did not leave solid — was written, and it took the
  rung from a standing 2/2 to **0/2**, twice, by the same mechanism: gravel falls into a seven-tall
  excavation and **plugs the alcove floor**, and those plugs are what the cast's water drains away
  through instead of pooling. Both failures show `tidy.0` removing `-7,56,36=gravel`,
  `-9,56,36=gravel`, `-8,57,36=gravel`, then `drain.6 = 等了 200 tick 仍有流体：-7,56,36 = water`
  where the passing runs read `drain.0…6 = 壁龛已排干`, then the body floating in it
  (`climb.4…10 = -7,56,36 onGround=false water=true`) and the top-row pours failing on their own
  flooded line. `tidyTheAlcove` stays cobblestone-only, with that measurement written beside it.

- **A pick cannot take water out of the doorway, and the doorway clear only had a pick.** A portal
  wants six empty interior cells, and the cast leaves cobblestone slag in some of them — so
  `clearTheDoorway` mines them, which is right for slag and a no-op for a fluid. The first ladder run
  ever to cast all ten cells died exactly there: `portal.slag = 2 格要清：-9,57,38=water
  -10,58,38=granite`, the granite went, six hundred ticks were swung at the water, and
  `portal.doorway = 还堵着：-9,57,38=water`. The water is not condensation — it is the alcove's own,
  arriving through the corridor cell immediately behind that doorway cell, which the same run's
  `drain.9 = 等了 200 tick 仍有流体：-9,57,37 = water` names. The rung's long-standing wet alcove
  stops being a cost here and becomes the failure.

  Three steps now, in an order where each is useless without the one before: **dam** the corridor cell
  behind an interior cell when it holds fluid (a corridor cell is this rung's own spoil heap and
  nothing downstream stands in it), **wait** 120 ticks for what is already inside to run out now that
  nothing replaces it, then **plug** any fluid that is left with a cobblestone so the existing pick
  can take it out as a block. `portal.dam` and `portal.plug.<cell>` say which step acted, and on the
  rehearsal that lit the portal afterwards they said something worth having: the **dam did not take**
  (`-10,57,37(流动)→没堵上，还是 water`, most likely the body standing in the cell it was placing
  into) and the wait plus the plug carried it anyway — `portal.plug.-10,57,38 = 流动
  minecraft:flowing_water → 塞成 cobblestone`, `portal.doorway = 六格都清干净了`,
  `portal.cells=6/6`. A three-step remedy that reports per step is why that is readable at all.

- **The drain gate named a cause its own reading disproves.** `drain.N` has ended with
  `—— 水源没被收回来` ("the source was never picked up") for months, in runs where every one of the
  ten recovers reports `CONSUME`. With the source flag in place the answer arrived on the first run
  that printed it: `drain.6` through `drain.9` all read `（流动，没源就会自己退）` — **flowing water,
  no source anywhere**. Nothing is feeding the alcove; the water is still on its way out after 200
  ticks, in a seven-tall room whose floor the tidy has just unplugged. That is a wait to lengthen or
  a floor to leave alone, and the sentence that sent readers looking for a lost bucket is gone.

- **`drain.N` printed one sentence about two different worlds.** "Waited 200 ticks and there is still
  fluid" is true of flowing water whose source was recovered and of a source that never was, and the
  two want a wait and a bucket respectively. `JourneyForge.firstFluid` now states which:
  `（源块）` or `（流动，没源就会自己退）`. Read by the drain gate, by the mould's own flood check,
  and by the doorway.

- **A tower answered a flood by standing still in it, forty times.** `ascendByTowering` settles when
  the body is not `onGround` and tries again, which is right for a stumble and unbounded for water:
  a swimming body never becomes `onGround`, so the branch recursed on itself for the whole cap and
  every course was a no-op. Measured on the portal rung: `climb.4` through `climb.39`, thirty-six
  identical readings of `-7,56,36 above=air onGround=false water=true`.

  It now spends the washed-off allowance and then says so by name rather than looping —
  `climb.9.afloat = -8,58,36 浮在水里，8 次都没落地 —— 塔要站在地上才垒得起来，爬升到此为止` — after
  which `climbOut`'s walker fallback carried the body out (`exit.walkerFallback=True`) and the run
  finished 10/10. Bounded rather than refused outright, because the allowance is the same phenomenon
  one tick earlier and already carries a measured number.

### Changed
- **Rung 15 hunts endermen where endermen are.** Its javadoc has named the warped forest as the
  densest enderman ground since the first draft, and the rung has always hunted from wherever rung 14
  stopped — a fortress, which is `nether_wastes`. It now surveys for a warped forest with
  `findClosestBiome3d` (6 ms, bounded at 256 blocks because the crossing one rung below has been
  watched fail whole at 399) and walks there. **Neither failing to find one nor failing to reach one
  is a rung failure**: endermen do spawn in `nether_wastes`, the biome only changes the rate, so both
  say so by name and hunt where the body stands.

  Two supports went in with it, and both earned their place on the first run.
  **A dry round now costs a round, not the rung** — one quiet minute used to jump straight to the
  verdict, so "six hunts" meant sixty seconds and 118 000 ticks of budget went unspent; measured,
  `enderman.found` went **0/6 → 2/6** with both found endermen killed (695 and 307 ticks).
  And **`census`** counts what is actually alive at 48 and at 128 blocks, split into endermen and
  everything else, with the pinned chunk radius printed beside it — because `enderman.found=0/6`
  is printed by three different worlds (nothing spawns; plenty spawns but endermen are rare; endermen
  exist outside the 48-block search box, and vanilla spawns 24–128) and it ends the search without
  separating them.

  **It separated them on the first run, decisively:**
  `hunt.1.dry = … 末影人 0 只在 48 格内、0 只在 128 格内；128 格内怪物共 107 只
  {zombified_piglin=62, piglin_brute=17, piglin=28}`. A hundred and seven monsters and not one
  enderman — spawning is healthy and the biome is the whole answer. The rung is still red (two kills
  against a bar of four, and both dropped nothing), and the walk that would fix it did not get
  through: `warped.around.1 = 脚下=lava 身处=lava 头顶=lava … 身体泡在岩浆里` at `14,22,3`, which is
  the same nether crossing that stops rung 14 — one upstream problem, not two bugs.

- **`forge.carved` said `完成` directly above `carve.stuck=12 格挖不动`.** Two rows written by the
  same method one line apart, one of them a caption that the excavation finished and the other a
  measurement that twelve of its cells are still rock. It now reports what it did —
  `63/67 格开了，4 格没挖动 —— 见 carve.stuck，壁龛不是完整的` — and deliberately does **not** fail:
  stuck cells are not uniformly fatal (both 10/10 rehearsals carried four, at the ceiling), and the
  cell that actually killed the ladder run had been carved perfectly and refilled afterwards.

- **`carve.stuck`'s histogram now states what its key is measured from.** The key is height above
  wherever the body finished the carve, which is not the alcove floor and is not the same place
  twice; read without that y, the ladder run's twelve stuck cells sat at "0 and 1", which reads as
  the floor and is in fact the ceiling.

### Added
- **Why the first corridor cell the carve could not open resisted.** `carve.stuck` has counted these
  for several runs and cannot say a word about the cause: a cell the body never reached, a cell it
  stood beside and ran out of budget on, and a cell walled in on all six faces all arrive as the same
  coordinate in the same list, and they want completely different work. The same three readings
  `noteCellDig` uses on the frame now go beside the first one — distance, `canBreak`, and how many of
  the six neighbours are full solid faces — and answered it on the first run that printed it, at both
  sites: `carve.firstStuck = -9,62,36=Block{minecraft:dirt}：身体 -8,59,36，距 3.2 格，canBreak=true，
  六邻实心 5/6，手上 minecraft:cobblestone`. **Not** walled in and **not** out of reach: the 240-tick
  per-cell budget ran out while the body could already have broken it.

- **Staging recipes for rungs 15 and 16, which had never executed a tick.** A rung with no recipe
  can only be reached by a fifty-minute climb that must first get past twelve rungs, so the top of
  the ladder was untestable by construction. `ENDER_PEARL` and `EYE_OF_ENDER` now share
  `crossToTheNether` with `BLAZE_ROD` — same portal arithmetic, different bag — and both ran for
  the first time within minutes of the recipe existing:

  - **16 PASSED**: `末影之眼 ×12（够一套门）` in 93 ticks. Six rods grind to twelve powder, twelve
    powder marry twelve pearls. Not one `ender_eye` is staged, because `eyeOfEnder` short-circuits
    on `already >= 1` and would have reported a pass over a craft it never ran.
  - **15 FAILED, usefully**: `身边 48.0 格内一只末影人都没有，等了也没等到`, with
    `hunt.biome=minecraft:nether_wastes`, `level.players=1`, `doMobSpawning=true`,
    `enderman.found=0/6` after 1200 ticks. The rung's own javadoc says the warped forest is the
    densest enderman ground; the rung never walks to one, it hunts from wherever it is standing.
    That is a gap in the rung, not in the recipe, and it was invisible while the rung could not
    start.

  Neither recipe stages the rung's subject: no enderman is summoned, no warped forest is searched
  for, no eye is handed over. Rungs 17–20 still have no recipe.

### Added
- **Rung 12 finishes. The body carves a mould beside the seed's lava lake, casts ten obsidian into
  it and lights the portal** — twice in a row, on the rehearsal, with the ten casts driven by one
  bucket that comes back full: `frame.cast=10/10`, `frame.obsidian=10/10`,
  `portal.doorway=六格都清干净了`, `portal.cells=6/6`,
  `light.cellAfter=Block{minecraft:nether_portal}`, `bucket.after=0 空 / 1 水`. 11 503 and 14 179
  ticks. The previous best was eight casts and nine cells opened.

  **This is a rehearsal, not a climb** — the rung's precondition is staged (`staging.calls=12`), so
  it says the rung's own work is sound and says nothing about arriving there off eleven real rungs.
  The ladder has not been run since.

  **And two passes are not a rate.** The two runs did not fail in the same places or succeed for the
  same reasons: one lost the backing behind cell eight and needed the mend below to get through
  (`cast8.backingMend … → 补回来了`), the other found that same cell still granite and never called it.
  The variance is in which cells the digging damages, and it is per-run.

### Fixed
- **A raise for a pour may not change columns.** `climbOut` pins the tower to a column and, when the
  body drifts off and cannot walk back, adopts wherever it landed — `driftKept`. That is right for an
  exit, where any column that rises is as good as another, and wrong for a pour, where the column
  **is** the geometry: move one cell sideways and the ray crosses the frame's plane somewhere else,
  so a check that `x=-10` works says nothing about `x=-8`. Measured on the tenth cell:
  `water9.raisedY=60/60` — the height reached exactly — over
  `climb.3.driftKept=-8,58,37 走不回 -9,37，改以这一柱为准`, after which the pour fired the same wrong
  ray from `-7,60,37` three approaches running, which is this file's own "a retry that changes
  nothing" arriving as a consequence rather than as a separate bug.

  Two changes, and the first is the one that matters. The raise column was arithmetic — one cell back
  along `away` — and is now **chosen by the ray**: for each corridor cell at the row below the target,
  run the clip a bucket would run from the eye a body standing there would have, and take the nearest
  that lands the fluid in the target. The body's own column is at distance zero, so a column that
  already works costs no walk at all — and on the next run it verified and was taken:
  `water9.raise = … 在 -9,37 这一柱上垒台阶 … 站上去射线落得进目标格，钉住这一柱`, against the `-10,37`
  the arithmetic would have picked and the body could not reach.

  Second, such a climb refuses to adopt: `climb.1.pinnedLost = -9,56,36 走不回指定柱 -9,37 —— 爬升
  到此为止，不改柱`. It stops, it does not loop, and it is not a failure — the pour's own ray gate
  still decides, and on that run the cell cast anyway (`cast9.result=CONSUME`) off `liftInPlace`. A
  pinned climb also skips the `Goal.YLevel` fallback, which is column-blind by construction.

  `raisedY` now reports the column beside the height, because `60/60` was a true statement about a
  body two cells out of the column its aim had been computed for, and reading the height alone is
  what made a lost raise look like a finished one.

- **A climb entered directly inherited another rung's column.** `climbColX/Z` are static and only
  `climbOut` set them, so the obsidian rung's climb-back-to-the-gallery — which calls
  `ascendByTowering` itself — reached the drift branch carrying whichever column the previous rung's
  exit had left behind, and "corrected" toward a cell nowhere near the body. That entry point now
  establishes its own column and clears the pin.

- **The digging opened the mould's own backing, and nothing re-asked.** `forge.backings=十四格背板
  都还是实心` is a one-off declaration taken right after the carve, and by the ninth cast of the
  2026-08-13 rehearsal two of the fourteen were air — `-9,59,39` and `-9,60,39`, both read out of the
  saved world, both behind the column whose frame cells are dug from a body that pillars up into the
  doorway. Every bucket in this rung is aimed at the block BEHIND the cell it fills, so an air backing
  is not a leak, it is an aim with nothing to stop it: `cast8.stand` rejected both candidates with
  `-9,60,39 不是实心的，弹不出流体`, fell back to a merely standable cell, and `cast8.picks` measured
  the ray reaching `-9,60,40` and dropping the lava into `-9,60,39` — a cell behind the frame. The
  rung's own ray gate refused to spend the bucket, which is why the run reported a pour rather than a
  wall.

  Every pour now audits the block it is about to aim at and rebuilds it out of the cobblestone the
  body carries, at arm's length or not at all (`placeOn` reaches `gameMode.useItemOn`, which has no
  reach gate on this avatar). Measured, and the mend is read off the world rather than off the call:
  `cast8.backingMend = -9,60,39 背板是 air（在 -9,60,38 后面…）→ 补回来了（cobblestone）`, and the
  cast that had never happened then did — `cast8.picks = -9,60,39 cobblestone face=north → 落进
  -9,60,38`, `cast8.result=CONSUME`.

  A per-cast count of the fourteen goes beside it, silent while they are intact. That is what dates
  the loss: `backings.8` never fired and `cast8.backingMend` did, so the backing behind cell eight was
  lost **inside cell eight's own two digs** — not somewhere in ten round trips.

  **The loss is intermittent, and that is the reason to mend rather than to hunt.** Three runs on the
  same seed and the same geometry: one where the backing was gone and the pour could not spend its
  bucket, one where it was gone and the mend carried the cast (`cast8.backingMend … → 补回来了
  （cobblestone）` → `cast8.picks=-9,60,39 cobblestone face=north → 落进 -9,60,38` →
  `cast8.result=CONSUME`), and one where the same cell was still `granite` and the mend was never
  called. The silent run is a real reading rather than a wire that was never connected, which is what
  the per-cast count is for. `backings.9=1/14 … -9,59,39=air` fired in both of the runs that finished:
  the interior cell's backing goes too, and nothing aims at that one.

- **A climb refused a course it did not have to dig for.** `ascendByTowering` asked
  `fluidTouching(ceiling)` before asking whether the ceiling was solid, and `fluidTouching` answers for
  the six NEIGHBOURS as well as the cell — so an EMPTY ceiling beside the rung's own water ended a
  climb that would have broken nothing at all. Measured on the portal rung:
  `climb.0 = -9,57,36 above=Block{minecraft:air}` and, the same leg,
  `climb.0.wouldOpenFluid = -9,59,36 挖开就会放出 -9,59,37 = water`, leaving `cast8.raisedY=58/59` —
  a body one row short of the cell it had to pour into, in a column with nothing but air above it.

  The guard now runs only on the branch that mines, which is what its own note always described. Two
  raises in the next rehearsal went the whole way where they had stalled: `exit.gained=3/3 block(s)`
  and `water9.raisedY=60/60`, against `1/2` and `58/59` before.

- **`noStand` named the one cause it had not tested.** The stand refusal has four clauses and printed
  a single sentence — `… 和 … 都没有地板` — for all of them. `cell.0.noStand` said that about the
  mould's BOTTOM row, and an offline read of that run's saved world says `-9,55,37 = andesite`: a
  perfectly good floor, so the row was false. Naming the clause answered it, twice, on two later runs:
  `cell.0.noStand = … 站不了：-9,56,37 头顶 -9,57,37=Block{minecraft:cobblestone} 被占` — the head cell
  was full of the rung's OWN pillar litter, which `tidyTheAlcove` sweeps up two steps later. Nothing
  to do with floors. The step refusal is split the same way, and it is now the reading that says the
  upper rows want stairs: `垫不了：-8,57,37 脚下 -8,56,37=air 撑不住 —— 一块砖会悬空`.

- **`standMissed` could not tell a failed walk from a rounding artefact.** It reported the body's
  block cell, and a 0.6-wide box resting on a block's edge rounds to the neighbouring cell. With the
  continuous position beside it the reading is unambiguous:
  `cell.5.standMissed = 想站 -11,57,37，停在 -11,57,36 … （精确 -10.54/57.18/36.91，脚下 -11,56,36=air，
  想站那格脚下 -11,56,37=cobblestone）`. The body is at z=36.91 — **standing on the step it just laid**,
  90 mm short of the cell that would satisfy `Near(cell,2)`. The walk did not fail and a second step
  in the near rank would not help; what is 0.09 blocks out is the arrival test, which measures block
  cells while the body has a real position.

- **The portal frame's middle rows had no cell the dig could start from.** `ServerWorldDriver.mine`
  walks to `Goal.Near(cell, 2)`, and the alcove is hollowed floor-to-ceiling, so for a frame cell two
  rows up the nearest corridor cell is the floor at 2.83 blocks — outside the gate, so the dig never
  began and the cell reported itself simply shut. The stand is now chosen rather than assumed: the
  cell behind the frame cell (1.00 away) or the one below that (1.41), whichever already has
  something under it, and failing both a single cobblestone into the lower one's own support — a
  corridor cell resting on the untouched rock under the alcove floor, so the body steps up exactly
  one block onto it. `tidyTheAlcove` already sweeps cobblestone out of the corridor, so the step
  does not survive the cast.

  Bounded on purpose at one block. Rows four and five up would need two or three arranged as STAIRS
  — a stacked column is a wall the body cannot climb — so they keep `mine`'s own goal and are told
  by name how far short they were: `cell.8.noStand = -9,60,38 够不着：身体 -9,56,36 距 4.47 格（>2）
  … 这一格要的是楼梯不是一块砖`.

  Measured, and the measurement is the point. Three steps were placed and **all three were read back
  off the world**, not off the call: `cell.4.step … → 站得住了（cobblestone）`. Asserting the body's
  resulting position then found the next obstacle immediately —
  `cell.5.standMissed = 想站 -11,57,37，停在 -11,57,36，距 -11,58,38 还有 2.24 格`: the step stood,
  the body climbed it, and stopped one rank short. Without that assertion this would have read as a
  step that did not help. Same terminal failure as the baseline (8 casts CONSUME, 9 cells opened),
  so no regression.

- **The dig that opens a portal frame cell tunnelled through the portal.** `ServerWorldDriver.mine`
  is `walker.setGoal(Near(cell, 2))` with breaking on, and a walker asked to get near a cell inside
  a wall will happily mine through the wall — which here is the mould. On the ladder run of
  2026-08-12 it ended at `-10,59,34`, and `-10,59,34` is not a corridor cell: the corridor is the
  two ranks at z∈{32,33}, and that coordinate is an **interior cell of the portal's own doorway**.
  The save shows three of the six interior cells opened and `-9,56,34`, the cell it was sent to
  fetch, still granite. The rung then reported `第 1 格没挖开就要浇` about a hole it had dug in the
  thing it was building.

  The dig now walks to the corridor cell directly behind the frame cell first, under `NoBreak`.
  Measured before and after on the same rehearsal: the body's position moved from inside the
  doorway to `身体 -11,56,36（壁龛内）`.

  **Only when that cell has a floor**, and that clause was bought the expensive way. The first
  version sent the body behind *every* frame cell, including the upper rows — whose behind-cell is
  air over corridor, i.e. mid-air. The rehearsal that had been reaching cast 9 stopped at cell 5.
  Guarded on the support below, it is back to cast 9 (8 casts CONSUME, 9 cells opened, same
  terminal pour-line failure as before the change), with the bottom row — the row the ladder
  actually died on — now dug from the corridor.

- **Two of rung 12's diagnostics were inventing causes they had never measured.** The ladder run of
  2026-08-12 died on the portal mould's first cell and explained itself twice, wrongly:

  - `cell.0.refilled.3/2/1 = -9,56,34 又被 granite 填上了（上面塌下来的）` — printed on every retry
    that found the cell solid, which is *also* what a dig that never opened it looks like. Granite
    is not a `FallingBlock`; nothing fell; the cell had never once been air. `reopen` now carries
    whether it has ever seen the cell open and says `stillShut` when it has not.
  - `dig.cell.0 = … end=collect swept everything it could reach (broke 64/64 …)` — read off
    `botState().mine`, which only a `MineProcess` writes. This dig is not one:
    `ServerWorldDriver.mine(BlockPos)` sets `mineTarget` plus a walker goal and explicitly clears
    `process`. The line was reporting the last MineProcess to have run anywhere, so a cell that had
    never been touched read as a dig that had succeeded sixty-four times. Dropped, and replaced with
    the geometry of *this* dig: where the body stood, whether that was even a cell the rung
    hollowed, and what is in the corridor cell it should have dug from.

  The replacement paid for itself on the first run that used it: `dig.cell.5 = -11,58,38 仍是
  granite：身体 -11,56,36（壁龛内），距 2.2m，canBreak=true，该站的壁龛格 -11,58,37=air` — three
  facts, none of them previously obtainable, and together they name the next cut (see `TODO.md`).

- **The portal rung ate its own staircase, and reported a walker bug.** Rung 12's ladder run of
  2026-08-12 died at four casts with `走不上楼梯：停在 -10, 61, 21，楼梯顶 -9, 66, 21 在 y=66 ——
  楼梯是挖出来了，但走不上去（台阶被堵？跨不上去？）`. Both of the message's guesses were wrong.
  Read off the saved world, twelve of the thirteen steps were perfect and the thirteenth,
  `-9,65,22`, had **lost the block underneath it**: `-9,64,22` was air, so the step was a two-deep
  hole and the ascent fell into it. A missing support is invisible from above — the step cell reads
  air whether or not anything holds it up — which is why five ascents' worth of evidence rows never
  named it.

  Where the block went: seed 5471's lava lake at `-9,63,19` is a **cave** lake, not a surface one.
  Its roof is one block of grass at y=65 with open cavern at y=64 and lava at y=63, and both ferry
  legs of every cast cross it. On the third cast's return the body fell through, landed in the
  cave, dug down (making a 3-deep lava pit at `-10,61..63,19..21` that the lake then filled), and
  dug its way back to daylight through `-9,64,22` — the one cell joining that pocket to the
  stairwell. Positions in the run log trace the whole excursion: `-10,63,19` → `-10,62,19` →
  `-10,62,22` → out at `-8,66,21`, and then `[walker] ascend dead-zone UNREACHABLE move=stepUp
  node=-10,66,22 foot=-10,63,22` ninety times on the next ascent.

  Three changes, all in the rung. The flight is **audited** before every leg (thirteen block reads:
  support solid, foot clear, head clear) and the audit's one line is now in the failure message
  instead of two guesses. A fault is **mended** — cobblestone clicked back under a step through
  `useItemOn`, or the pick through a blocked cell — but only from arm's length, because `placeOn`
  has no reach gate on the server avatar and a repair the body could not walk to is not a repair
  the ladder earned. And the ferry walks now carry `NoBreak`: they cross ground the rung cut with
  its own pick, so a planned dig there is never the answer to anything.

  Verified by making the fault certain rather than by waiting for it —
  `-Prehearse=PORTAL_LIT -PbreakAStair=true` removes `-9,64,22` before the second leg, and the
  rehearsal read `cast0.stairsBroken=1/16 级坏了：-9, 65, 22 脚下 -9, 64, 22=air`,
  `cast0.stairsMend.0=… → 垫上了 … 现在是 cobblestone`, then **nine** clean round trips. The same
  run's first ascent found an unplanted fault nobody had known about: gravel had fallen into the
  stair bottom (`-9,56,36`), and the audit mined it out.

- **`mc.test.reset`'s manifest traced its own code path instead of reporting an effect.** The
  `keys` token was appended unconditionally after `releaseKeys()` returned, so a `releaseKeys()`
  that became a no-op would have kept every assertion on it green forever; `screen` said only that
  `setScreen(null)` had been called on something. They are now `keys:<names>` (absent when nothing
  was down, `→still:<names>` when the release did not take) and `screen:<class>` — read off
  `KeyMapping.isDown()` and `mc.screen` on the same client hop, before and after. Visible
  immediately: `wd.clientResetClearsEntry` now records `resetTokens=[screen:InventoryScreen,
  chat:11]` with **no** keys token, because that scene holds no key down.

- **`wd.clientResetClearsEntry` cleaned up with the verb it exists to break.** Its cleanup was
  `mc.test.reset`, so the run where the reset stops closing screens is exactly the run where the
  cleanup also stops closing them — and the open screen lands on `wd.clientResetReleasesKeys`,
  which needs no screen and has no idea why it is looking at one. Both scenes now clean up by a
  route they assert nothing about (`mc.client.screen.close`, and an explicit key release).

- **Rung 14 spent two of its three walk attempts on a body that was underwater in lava.** The
  nether crossing ends its attempt, records `脚下=lava … 0/4 面是墙`, and re-issues the identical
  walk order — twice. `no path (expanded=1)` there means *submerged*, not entombed, and the
  surroundings line said how many walls there were without ever saying the body was under the
  lava. A retry is now refused, loudly and by name, when the body is in a fluid or still falling;
  the surroundings line calls submersion out as the reason for `expanded=1`, and gained
  `onGround`, fall distance, health and the drop to the first solid block below — the readings the
  still-open half of that diagnosis (a plan that ends airborne over a cave) needs.

- **The planner's tuning lived in process-global statics, so any two bodies in one JVM overwrote
  each other's knobs.** `BotConfig.pfHorizonBlocks()` returned `0` whenever
  `pathfinderBoxedEscalate` was set, and `WalkerTickPrelude` wrote that static on *every* walker
  tick. On the integrated topology the client's Walker runs in the same JVM, so its boxed churn
  silently disabled the horizon for a search the **server** thread was running: `wd.horizon`
  compared `off` against `on=48` and got byte-identical results (`firstExpanded=633` both), i.e. the
  scene measured nothing and still reported a colour. `PathFinder` now takes a `PathTuning` source —
  `escalatedWhen(body's own clock)` for a Walker, `fixed(…)` for scenes that want a fixed override,
  `GLOBAL` only for finders with no body behind them. The `EscalationClock` was **already**
  per-Walker; the static was just a mirror so the planner could see it, so this deletes a channel
  rather than adding a mechanism.

  Two follow-ons worth knowing. `pinnedBaseline()` could never have fixed this: the field is in
  `NON_PERSISTED`, which `persistable()` excludes — **the exclusion that makes a field correct for
  persistence makes it invisible to isolation.** And an intermediate version froze the tuning at
  construction, which fixed isolation and broke *liveness*: escalation is a sticky tick timer and a
  time-sliced search spans ticks, so a search that started while escalated stopped noticing the
  lapse and ground on instead of re-capping — the JVM died under the 60 s watchdog with the Server
  thread RUNNABLE in `Diagonal.clearColumn`. Isolation and liveness are separate properties and the
  obvious fix for one traded away the other; the shipped version reads a per-body *source*, not
  frozen *values*. Two other statics of the same kind (`fleeActive`, `walkerDigActive`) are still
  globals — parallel scene execution stays unsound until they move too.

- **A search could exceed the server's hang watchdog and kill the JVM.** `PathFinder` now enforces
  `CEILING_MS = 8_000` across all of a search's slices, well above any legitimate search and well
  below the 60 s watchdog, and `LOG.warn`s with owner/expanded/goal when it clamps. This is a
  backstop against process death, **not** a policy about search length: the 58 scene sites that set
  `pathfinderMaxMs = Long.MAX_VALUE/2` are correct and were left alone, because `maxNodes` is the
  deterministic bound — a wall-clock cap would make the same scene pass or fail depending on how
  busy the box is. The ceiling has fired zero times across all six topologies.

- **The driver's world view did not follow the body through a dimension change.**
  `ServerWorldDriver` built one `LevelWorldView` in its constructor from the body's creation level
  and handed that same view to every `BotProcess` and to the `Walker` for its whole life. From the
  moment the body stepped through a nether portal, **every pathfind planned across overworld terrain
  at nether coordinates** — and nothing said so: the walker planned, drove, and reported an ordinary
  failure to arrive, indistinguishable from bad terrain or a tight budget. `world()` now rebuilds
  when the body's level changes, and the two `tick()` call sites go through the accessor (they were
  reading the field directly, so fixing only the getter would have changed nothing).

  A cheap way to detect this class of bug: read the 27 cells around the body twice, once through
  `view.isSolid` and once through `level.getBlockState(...).blocksMotion()`. On one level those are
  the same expression and agree 27/27, so a *single* disagreement proves two levels.

- **A finished smelt and a smelt that never happened were byte-identical.**
  `SmeltProcess.collect()` shift-clicked the furnace result slot and then reported DONE
  *unconditionally*. `AbstractFurnaceMenu.quickMoveStack` → `moveItemStackTo(stack, 3, 39, true)`
  returns false and **moves nothing** when all 36 player slots are full, so the process ended with
  `lastError == null` and the ingots still in the block entity. It read as "the mine produced no
  ore". Intermittent for a reason unrelated to smelting: the body stands beside the furnace for 200
  ticks per item with `touchNearbyEntities()` running every tick, so the slot its own ore vacated
  refills from the ground. `collect()` now re-reads the result slot and reports what it could not
  take back; `init()` also stops using one message for both "no furnace in the bag" and "furnace in
  the bag, nowhere to put it".

### Added
- **A rehearsal mode for single rungs** — `./gradlew :fabric:runRehearsalServer -Prehearse=<STAGE>`.
  The journey ladder runs 20 rungs on one persistent body, so testing an upper rung meant replaying
  everything below it and winning a coin toss; measured, about half of runs never reached rung 12,
  and two committed fixes to it went unexecuted across three consecutive runs. A rehearsal stages one
  rung's preconditions and runs it alone in 2–5 minutes.

  Staging is allowed there, so four independent guards stop a green rehearsal from ever reading as a
  green climb: its own scene names (`wd.rehearse*`), its own property/task/runDir, every arrangement
  counted so the row carries `staging.calls=N`, and its own verdict reading
  `REHEARSAL — not a climb`. The real ladder's `staging.calls=0` assertion is byte-unchanged.

- **The journey body now joins the player list** (`-Dworlddriver.realPlayerBodies=true` on
  `runJourneyServer`). Vanilla gates a surprising amount of the endgame on `level.players()`, and a
  `FakePlayer` that never went through `PlayerList.placeNewPlayer` is not in it: `EndDragonFight.tick`
  creates **no dragon** while that list is empty, `BaseSpawner.isNearPlayer` never turns a fortress
  spawner, and nothing spawns naturally. All three fail silently, and the End still builds its
  crystals — so only the dragon looks missing. Verified not to destabilise the eleven rungs below it.

### Added
- **All six topologies re-verified GREEN with the new scenes, and coverage is now stated as a
  matrix rather than a number.** No single topology runs everything, so "all scenes pass" is only
  meaningful as a union — and the union is complete:

  | topology | pass | skipped |
  |---|---|---|
  | dedicatedServerFabric | 225 | 20 (client-only) |
  | dedicatedServerNeoforge | 225 | 19 |
  | integratedServerFabric | 244 | 1 (needs NeoForge `itemhandler`) |
  | **integratedServerNeoforge** | **244** | **0** |
  | dedicatedServerWithClientFabric | 239 | 6 |
  | dedicatedServerWithClientNeoforge | 239 | 5 |

  `integratedServerNeoforge` skips nothing, so every scene in the suite executes and passes
  somewhere. The 20 that skip on a dedicated server are the `mc.client.*` family, which needs an
  integrated server's handlers; all 19 of the 20 that are not loader-gated were checked individually
  as `dedicated=skip / integrated=pass`, because "it skipped" and "it passed" are the same green row
  and only one of them is coverage. Both `dedicatedServerWithClient` runs also judged their
  second-process client probe: `client.damageSourceAcrossTheWire` PASS `[source=outOfWorld, lost=2.0]`.

  **A RED along the way was contamination, not a defect, and is recorded because it cost a
  diagnosis.** An `integratedServerNeoforge` run reported four required failures — three client
  scenes plus one new one. Re-run clean it is GREEN and all four pass. That run was a relaunch of a
  topology an interrupted job had left mid-flight, with orphaned game JVMs (an `architectury` one
  among them) still alive. Reap the strays and re-run before reading a verdict from a log.

- **The three capabilities the pinned probes could not answer now have scenes**, and one of them
  changed the plan.

  **`wd.serverFightsAFlyingBlaze` — melee cannot win under open sky.** 3000 ticks, the blaze taken
  from 20 health to 8 and never finished, hovering 6–8 blocks up against a melee reach of about
  three. The same fight **in a closed room takes 40 ticks** — a 75× difference from changing the
  room rather than the code, which is why no ranged-combat subsystem was written to solve what turns
  out to be a geometry problem. **A ceiling alone was not enough either**: a bare lid over an open
  floor got the blaze to 2 health and still lost it, because the mob drifted out past the lid's edge
  and climbed above it. Walls first, then a ceiling — sideways is how it escapes.

  Only the room is asserted. The open round is recorded, because it was first written as
  `expect(open.dead).isFalse()` and a NeoForge run then finished the open blaze at 2.0 health left:
  an assertion that a fight is NOT won sits on the wrong side of the dice, and one lucky run would
  redden the gate for the one reason that is good news.

  **`wd.serverEarnsAnEnderPearl` — a mob whose defence is to stop being there is still killable.**
  6/6 killed, 4 pearls, inside a closed box so that every teleport lands back in the thing being
  measured. A real stronghold is not a box and the scene says so.

  **`wd.serverBreaksAnEndCrystal` — the verb the dragon fight opens with.** The crystal breaks to a
  melee hit and the body is still standing; that second reading is weak while the avatar is
  invulnerable, and it says so on the row. The 20–40 block pillar is `ascendByTowering`'s problem,
  not this scene's.

- **`wd.serverDamagesTheDragon` — the summit's own question, answered.** A dragon is not hit like a
  mob: `EnderDragon.hurt` refuses every direct hit, damage only lands through an `EnderDragonPart`,
  and only the HEAD takes it undivided. The prediction was that the combat loop would swing at a
  position with no hittable hitbox and report a fight it was winning while the boss bar never moved.

  **It was wrong, and measuring beat predicting.** The existing `CombatProcess` took the dragon from
  `200.0` to `197.3` unchanged, and a hit aimed at the head lands `2.75`. Identical on both loaders.

  **The control in this scene was itself the first bug.** Its manual head-hit step read a flat zero
  — which looked exactly like "a driven body cannot hit a multipart boss" — because it reset
  `hurtTime`, the red-flash timer, and left `invulnerableTime`, the one that actually refuses damage
  for 20 ticks; and because it reset the attack-strength ticker *after* swinging, so every swing
  landed at the bottom of the cooldown curve. Both are fixed and both are written down in the scene,
  because a control that measures the rig reads exactly like a capability that is missing.

  Scoped: the dragon is pinned with no AI, so a red means the attack path cannot reach a multipart
  entity and cannot also mean the body could not catch up. Crystals, perching and the flight pattern
  have no scene yet and the javadoc says so.

- **`wd.serverBuildsAndLightsAPortal` — the whole of N5 from a flat floor.** A bucket, a
  flint-and-steel and a pile of cobblestone go in; a lit nether portal comes out: 42 backing blocks,
  24 wall blocks, ten casts from one bucket, six portal cells. 549 ms, green on both loaders first
  try. The three scenes it builds on all work a wall that was **staged**, and in the field there is
  no two-thick wall waiting beside the lava — so what this adds is the step the rung actually spends
  its blocks on.

  Two shapes worth keeping. **Placement is exact and reach-free**: `ServerPlayerAvatar.useBlock`
  builds its own `BlockHitResult` rather than ray-tracing for one, and vanilla's distance check
  lives on a packet path this body never uses — so the mould is bookkeeping, not navigation. And
  **order is what makes every block placeable**: the backing slab goes up first, bottom-up, each
  block resting on the one below; every solid cell of the front layer is then placed against the
  backing behind it. Building the front layer first strands every cell whose lower neighbour is one
  of the sixteen that must stay air.

- **`wd.serverOpensTheEndPortal` — twelve eyes into the frame, then across.** Inserting an eye is
  `EnderEyeItem.useOn`, the same `useOn`-only shape as the flint-and-steel, so a body reaching for
  `useItemInHand` would get `PASS` and a frame that never fills. Green on both loaders: 12/12 eyes,
  the portal forms, transit takes 2 ticks, and the landing is asserted against
  `ServerLevel.END_SPAWN_POINT` rather than merely against the dimension — drift 0, standing on the
  arrival platform's obsidian.

- **`wd.serverEarnsABlazeRod` — a driven body's kill counts as a PLAYER kill.** The blaze rod is the
  one drop on the road to the dragon that vanilla gates on `killed_by_player`, so a body that hits
  hard enough to kill and does not register as a player clears fortresses and crafts no eyes.

  **The first version of this scene could not tell three explanations apart.** It killed one blaze,
  saw an empty floor, and asserted — but a blaze rod is a uniform 0..1 roll, so "the condition
  failed", "the die came up zero" and "mob loot is off" all produce the same evidence. It now reads
  the `doMobLoot` gamerule outright and kills **twenty-four**, recording the per-kill tally:
  measured 11 rods from 24, which is the roll behaving normally and puts an all-zero run out of
  reach of a gate that runs on every commit.

  Scoped deliberately: the blaze is **pinned** the way `wd.serverCombat`'s zombie is, so a red means
  "the drop does not reach a driven body" and cannot also mean "it flew away". Whether the melee
  loop can reach a blaze that is actually hovering is a separate question with no scene yet, and the
  javadoc says so — a green row that quietly meant "we never fought a flying mob" is the shape of
  coverage this suite exists to refuse.

- **`wd.serverCastsAPortalFrame` — ten obsidian from one bucket, and the route is not the obvious
  one.** A frame is a vertical ring around a 2x3 interior and every one of its ten cells touches that
  interior, so the water goes **into the interior cell adjacent to whatever is being cast** and is
  then **carried to the next one**. That reproduces the proven single-cast geometry for every cell
  and needs no fluid flow at all — the conversion is a neighbour update, not a fluid tick. The single
  bucket falls out of the ordering for free: empty after placing the water so it can fetch lava,
  empty again after pouring the lava so it can take the water back. The reservoir is visited once.

  Three orderings were tried and measured first, each of which failed as a *broken bucket* rather
  than as a wrong plan. Running water down the outside of a one-thick face reached `0/10` — falling
  water spreads where it LANDS, and a pocket in a vertical face has no floor to spread along. Filling
  every cell with lava and dousing at the end left the bucket full after the first miss, so cell two
  reported "no empty bucket" and the fault was two steps upstream. One source in the interior cannot
  reach all ten however long it is given: water does not flow up.

  **The top row is a vanilla rule, not a bug.** `LiquidBlock.shouldSpreadLiquid` looks at
  `{DOWN,NORTH,SOUTH,WEST,EAST}.getOpposite()` around the lava — above and the four sides, never
  below. Water under lava converts nothing, so the top pair casts against a notch cut one block
  higher, and a frame carved into a wall costs **twelve** cells of digging rather than ten. Getting
  it wrong shows up only as two cells of standing lava.

  Two other numbers the ladder now owes: a scoop takes the **source** and leaves air, so ten casts
  need ten distinct lake cells and ten walks; and the body has to stand with the target at **eye
  level**, because a bucket fills the neighbour of the face its ray lands on and a steep ray enters
  the wall a block low — measured, it hit the obsidian just cast there and left the water behind.
  Result: `10/10`, ten water moves, interior dry, bucket home. Green on both loaders, ~330 ms.

- **`wd.serverEntersTheNether` — a driven body walks through the portal it lit.** ROADMAP N6's first
  question, asked in a second here rather than at the bottom of a shaft after an hour of casting.
  The frame is staged, the **lighting is not**: it goes through the same flint-and-steel path
  `wd.serverLightsPortal` proves, so what the body tries to walk into is a portal it built. Transit
  takes 82 ticks, which is a player's own portal wait. Green on both loaders.

### Fixed
- **A driven body changed worlds but not places.** `ServerPlayer.changeDimension` does not move the
  body — it sets the new level and then delivers the destination **through
  `connection.teleport(...)`**, which both loaders' fake players swallowed along with every other
  packet-listener method. The body therefore arrived in the new dimension holding its **old
  coordinates**: an overworld portal at `x=100001` landed at nether `x=100001` instead of `x=12500`,
  87 501 blocks out, at `y=221` against a logical height of 128, standing on air, with the return
  portal correctly built 87 501 blocks away where the body should have been.

  **The dimension assertion passed the whole time.** It would have gone on passing while the fortress
  search, the stronghold and the End all looked at the wrong world. What caught it was computing the
  destination independently — `DimensionType.getTeleportationScale`, 8:1 — and asserting the landing
  rather than the arrival.

  The blast radius is wider than portals: `ServerPlayer.teleportTo` routes through the same call, so
  *no* vanilla mechanism could reposition a driven body, including the End portal and the dragon's
  gateways. The fix is one shared listener, `AvatarNetHandler`, whose `teleport` does what vanilla's
  real listener does in `internalTeleport` (`absMoveTo`) minus the packet there is nobody to send.
  NeoForge's `FakePlayer` is not ours to subclass, but `ServerPlayer.connection` is a public field,
  so the loader shim installs the listener over the stub NeoForge built — the body keeps the
  `FakePlayer` identity mods look for and only the listener changes. Both loaders now land at
  `12499, 118, 12500`: one block of drift, in a real portal, on obsidian, under the roof.

- **`wd.journey11Obsidian` — the obsidian rung is scripted.** Walk to the lava the survey found,
  sink a shaft as deep as that lava is, tunnel the last cells to it, fill the bucket, climb the same
  height back, and pour into standing water. The assertion is on the cell the rung NAMED before the
  pour: obsidian appearing somewhere proves the fluids met, obsidian appearing where the body aimed
  proves the body put it there, and only the second is something a portal can be built on.

  **One block, not the portal's ten, and that is the rung rather than a shortcut.** Obsidian cannot
  be carried — taking it back needs a diamond pickaxe — so a frame is cast in place and where the ten
  cells go is `PORTAL_LIT`'s question. The portal's own plan (carry water DOWN once, leave it as a
  source, shuttle lava with the one bucket) is also written down in the rung, together with why it
  is not what this rung does: water placed at the bottom flows along any opening at its own level,
  and the opening this rung must make is the one to the lava. Water reaching the pool converts the
  very source the bucket was going to draw from, so the two halves race over a tunnel two or three
  cells long — about fifteen ticks. Pouring into water that is already standing at the surface has
  no such race and measures the same four verbs.

  The tunnel to the lava **drives itself and needs no route**: a bucket fills along the ray the body
  is looking down, so whatever that ray hits first IS the obstruction. Aim at the source, ask
  vanilla's own pick what got in the way, mine that, look again. Every block it breaks is on the
  line to the goal, so it cannot wander.

- **`wd.serverCastsObsidian` now places its water from the bucket too, and asserts the source
  survives.** The first version staged the water with `setBlockAndUpdate`, which proved the
  conversion and left *can the body put water where it wants it* unanswered — the one verb of the
  cast the arena had not tested. It now empties a water bucket against a wall so the water lands one
  cell above the mould, fills from lava, pours, and then checks **the water is still a source**.
  That last reading is the whole of the one-bucket claim: a cast that ate its water would need a
  fresh trip to open water for each of the portal's ten blocks, and nothing about the obsidian would
  have said so. Green on both loaders, 161–175 ms.

- **`wd.serverCastsObsidian` — a server-side body can cast obsidian, and N4 needs no new
  engine capability.** Written as a capability probe BEFORE the rung rather than after it, because the rung
  is a descent of tens of blocks to this seed's nearest lava and that is an expensive place to
  discover the body cannot work a bucket. It fills an empty bucket from a lava source, empties it into a chosen
  cell, and asserts water converts that cell to obsidian — the cast, not the crust, because obsidian
  that already exists needs a diamond pickaxe to take. Green on both loaders in 160 ms, promoted to
  required in the run that first saw it green.

  Four wrong answers on the way, each cheap and each worth knowing before writing the rung. **The
  verb is not `useItemOn`** — that is the block-targeted path and a bucket has no `useOn`; buckets do
  their work in `Item.use`, which the driver exposes as `useItemInHand`. **Aim is an input, not
  decoration**: `use` ray-traces from the eyes, so where the body is looking is the whole of the
  targeting. **An aim needs a tick to land** before the use reads it — without one the fill silently
  used the previous aim. And **a mould needs a bottom**: aimed at a cell with air beneath it the ray
  hit nothing and the pour came back `PASS` with the bucket still full, which reads nothing like the
  `CONSUME`-with-empty-target of a pour that landed somewhere else. The probe records both, because
  a miss and a misplacement are different bugs.

- **The journey's floor is IRON.** `JourneyLedger.FLOOR` moves FURNACE → IRON and
  `JourneyStage.IRON` becomes gating, which is the fifth time this ratchet has been raised and the
  first time it took real work to earn. IRON had been green before and failed on the same code the
  next run, so it was deliberately left below the floor on the rule the number exists to enforce —
  *the floor claims a rung works, not that it once worked*. Promoted on three consecutive green runs
  of the same code (铁锭 ×4 / ×6 / ×6, `stagingCalls=0`). PORTAL_KIT stays frontier: green on one of
  those three, and both failures have since been fixed but not yet re-measured.
- **`firstLava` is surveyed.** It had been UNSURVEYED because the survey asked the surface question,
  and a swamp surface truthfully has no lava; that is a correct answer to a question nobody wanted
  asked. `nearestInBand` scans an absolute height band instead and answered identically on two
  consecutive runs — `(84, -14, 47)`, a ~77-block descent, which was written down here and in the
  stage javadoc as ROADMAP N4's bill. *That number did not survive the next widening of the search:
  see the entry under Fixed. The pool is at `(68, 27, -1)` and the descent is thirty-six.*

- **The journey climbs to PORTAL_KIT, and the iron rung works two veins to pay for it.** Seed 5471's
  first iron vein is one ore deep: the rung mined it out and reported `broke 1/8, no reachable
  target` with two ingots banked, which is terrain rather than driver and is not something a bigger
  quota can fix (a wider radius makes it worse — see the drift note below). So the survey now finds a
  SECOND vein at least twelve blocks from the first (`secondIron`, `secondIronDescent`), and the rung
  digs it only when the first came up short of what the kit costs. `PORTAL_KIT` itself dropped from
  two buckets to one. **The reason first given for that was wrong** and is corrected here: "pour
  water over the lava sources and they turn to obsidian where they stand" is true and useless,
  because taking obsidian out of a lava lake needs a diamond pickaxe. A portal is cast, not found —
  a mould, then lava placed into it one bucket at a time. One bucket is still right for a different
  reason: water is carried **once**, placed as a source at the build site where it stays and flows
  over each cell, so the same bucket shuttles lava for all ten frame blocks. Four ingots, not seven.

- **The ladder fells a tree when a craft runs out of wood, instead of pre-paying for a tax nobody
  can size.** The wood bill has been raised twice — 3 → 5 → 8 — and eaten through both times. The
  measurement that ends the argument: a run with **eight logs** (thirty-two planks) reached the stone
  rung holding `planks=3`, `craftingTable=0`, `sticks=2`, `cobblestone=32`, and failed
  `缺 1 个 oak_log`. The recipes it had actually paid for cost **nine** planks. The other twenty went
  on crafting tables that are neither standing within 32 blocks nor lying as drops — simply gone.

  A bill cannot be sized against a tax that varies like that, so this stops trying. A craft that
  fails for want of wood now walks to the nearest trunk of the route's own species, cuts it, and
  tries once — one retry, and only for the one cause a retry can fix, since every other error would
  repeat identically. Same lesson as the wedged walk: **a retry has to change the question.**

  The vanishing table itself is an engine-side finding and is logged as one; `wd.serverCraftTableReclaim`
  passes in an arena, so whatever loses it is not visible at that scale.

- **Every craft now keeps its own table, instead of three of them remembering to.** `CraftProcess`
  places a crafting table and reclaims it only on a best-effort basis, so a craft that walks away
  leaves one standing — four planks, one log, every time the ladder buys another. Three of the
  ladder's crafts had a `reclaimTableIfLeftStanding` backstop written by hand; the planks, the
  sticks, the wooden pickaxe and the flint-and-steel did not.

  Measured: a run holding **six raw iron** smelted none, because the furnace it had to re-craft
  needed a table, the table needed four planks, and there was not one log left —
  `furnace.remadeError=缺 1 个 oak_log`. The wood bill has already been raised twice for this
  (3 → 5 → 8) and raising it is treating the symptom: the recipes are fixed and **the tax is what
  varies**.

  So the guard stopped being something each rung has to remember. `craftKeepingTheTable` is
  ensure-a-table → craft → take it with you, and every craft on the ladder goes through it. The
  reclaim is one block-break at a fixed cost immediately after the craft — the only moment the table
  is certain to be in reach, since thirty seconds later the rung has walked a hundred blocks and the
  32-block search that would find it again is looking in the wrong place. It also records
  `<item>.crafted` and `<item>.craftError` for every craft, which is what turned the last failure
  from *"the craft verb is broken"* into *"it ran out of wood"* in one line.

- **The furnace guard had none of the crafting table's recovery, and no reason attached to its
  failure.** `ensureCarrying` — which the iron rung uses to get a furnace back before smelting — did
  three things the table's guard does not: it *noted* a standing station instead of mining it back,
  it re-crafted through a bare `CraftProcess` with no table guard and no room check, and it recorded
  no error.

  Measured: `furnace.standing=none`, `furnace.remade=true`, `furnace.after=0`, and then
  `smelt.lastError=需要熔炉（背包里没有可放置的熔炉）` — a rung that mined **4 raw iron** and smelted
  none. The re-craft had failed for want of a crafting table, and the only trace of that was a count
  of zero two lines later: an outcome with no reason attached, which reads as *the craft verb is
  broken* rather than *it was never given what it needs*.

  It now mines a standing station back into the bag, re-crafts through `ensureCraftingTable`, records
  `<station>.remadeCount` and `<station>.remadeError`, and reclaims the table afterwards — the same
  shape the table's own guard has had since the tax it charges was measured.

- **`wd.journey11Obsidian` is green in the field.** The ladder's peak is now OBSIDIAN, floor IRON,
  `staging.calls=0`: `fill.hand=minecraft:bucket, fill.result=CONSUME, lava_bucket=1,
  fill.sourceAfter=air` — the source consumed, the bucket full — then `cast.hand=minecraft:lava_bucket,
  cast.result=CONSUME, cast.cellAfter=Block{minecraft:obsidian}, bucket.after=1`. Obsidian in the
  cell the rung named, from lava the body fetched itself, with the bucket back in hand.

  **The floor is now PORTAL_KIT** — ROADMAP N0 through N3, the sixth time this ratchet has moved.
  It had been green six times *before* this and was still held down, which is the point of the rule:
  those greens were not on one code base, and one of the reds was real. The kit costs four ingots,
  the vein loop was written to work three veins, and only two were ever baked. A rung that passes
  because the terrain was generous is not a rung that works — what made it promotable was finding
  that, not running more runs. Four consecutive greens, 铁锭 ×6 / ×6 / ×11 / ×6.

  One bound rides along and is not hidden: every green row on this track carries
  `body.invulnerable=true`. The ladder proves what the driver can DO, never that a body survives it.

- **OBSIDIAN was promoted to gating and demoted one run later, and the round trip is worth more than
  the promotion was.** It had exactly the three consecutive greens the bar asks for — identical code,
  casting at `-6, 62, 55`, `staging.calls=0`, `exit.gained=36/36`. The next run tunnelled into a
  cave, fell from y=27 to **y=14**, and reported *"descended to the lava layer and cannot see a lava
  source"* — true, and reading like a survey problem about a pool the three runs before had walked
  straight up to.

  **Three-of-a-kind cannot see a one-in-four hazard.** The bar is not wrong for the rungs below it;
  it is too small a sample for a rung whose last leg mines horizontally through rock nobody surveyed,
  because that leg can open a floor. The demotion is the ratchet working — the verdict scene went
  red the moment the run fell short of a floor that had just been raised — not a mistake it failed
  to prevent.

  Fixed with `TUNNEL_CLIMB_BACKS`: when no source is in reach and the pool sits more than two blocks
  *above* the body, the tunnel now towers back to the pool's level and resumes instead of reporting
  a missing pool from underneath it. Twice per rung, and the budget is threaded through the tunnel's
  recursion rather than re-defaulted per step — a budget that resets every step is not a budget.

  **This fix is still unexercised in the field.** The three runs after it never fell — `tunnel.fell`
  appears in none of them — which is what a one-in-four hazard does to a three-run sample, and is
  exactly why those greens are not evidence the recovery works.

- **The food rung looks further instead of looking elsewhere, and two wrong premises died to get
  there.** It failed with `[minecraft:cat, minecraft:frog]` within 96 blocks.

  *First premise — "spawn is where the animals are."* A run then found the same cats and frogs within
  96 of the stone rung's endpoint **and** within 96 of spawn. Walking home changed nothing.

  *Second premise — "then survey the herd at spawn on tick one and walk there."* Written, and then
  **refuted by the survey built to support it**: with the chunk pin applied and the load waited out,
  it reports `无` too. This seed has no food animal within 96 blocks of spawn, at tick one or later.
  The runs that eat find their cow 77 blocks from wherever the wood and stone rungs carried the body,
  which is well over 96 from spawn.

  So the thing to change is the radius, not the standpoint: on a miss the rung pins 11 chunks, waits,
  and re-scans at 176 blocks (`prey.wide`), then walks to what it finds. Not the default, because
  pinning 23×23 chunks to answer a question 96 blocks usually answers is a cost every run would pay
  for the benefit of one. The spawn-time survey stays as a recorded measurement — it is the evidence
  that the wide search has to exist — but no longer feeds a landmark nobody reads.

  Two traps on the way, both worth keeping. **It cannot live in recon**, which is where it was first
  written: recon runs before the body exists, and every prey query is about the body's surroundings
  (`nearestPreyTarget` centres on it, `seeAtLeast` pins ITS chunks) whereas recon reads terrain, which
  needs only a level — six runs died on `还没有身体` first. And **a survey that cannot see reports an
  empty world**: scanning on the same line as `seeAtLeast` sees only already-loaded chunks, because
  the ticket applies on an await tick. Widen, *wait*, then look.

- **Two tests of the same condition that disagree are a bug generator.** `makeRoomForAStation` asked
  whether a station could be placed by checking **4 cells** at foot level with one set of predicates;
  `PlaceNearby.place`, the code that actually does the placing, checks **24** (8 offsets × 3 layers)
  with different ones. The helper therefore said "no room" where the placer would have succeeded —
  visible as `station.noGround` in two green runs whose craft worked anyway — and, when it "fixed"
  that, it walked a **fixed compass direction ±4 blocks**, which is a guess: measured, a body went
  from `62,63,64` to `62,63,60`, one unusable cell to another. In a swamp that is the normal case,
  because the body is standing in water and the neighbouring "ground" is more water.

  The furnace rung then failed twice in six runs — `furnaces crafted (0)` with `cobblestone.before=25`
  and `craftingTable=1`, every material in hand and nowhere to put anything. That is a floor rung, so
  the verdict went red both times.

  Three fixes: `placerWouldFindRoom` is a copy of the placer's own 24-cell predicate (one of two
  disagreeing tests is always wrong, and the failure never says which); `groundWithRoomNear` walks to
  a cell that *answers* the question rather than in a direction; and the attempt evidence is indexed
  (`station.steppingOff.N`) because three attempts under one key describe only the last — the same
  defect the pickup keys had.

- **Counting green runs is the wrong promotion criterion.** OBSIDIAN was promoted on three greens and
  regressed the next run; the bar was raised to five on the reasoning that a one-in-four hazard is
  invisible to a three-run window; it was promoted on five and **regressed the next run again**. The
  floor is back at PORTAL_KIT.

  Both regressions were the *same* hazard — the tunnel holing a cave roof — and both times the
  qualifying runs had simply never hit it. `tunnel.fell` appears in none of the three, and in none of
  the five. So the larger sample was never the fix: **a run that does not exercise a known recovery
  is not evidence about that recovery**, and no number of such runs adds up to any. This was written
  down explicitly before the second promotion and then promoted past anyway.

  What a promotion of this rung has to show is therefore not a count but **each known hazard's
  recovery observed working at least once**. The fall recovery has now been seen twice and failed
  both times, the second unambiguously: `climb.0.stalled=stuck (no Y gain in 60t — out of blocks?)`
  beside `climb.0.state=onGround=true inWater=false y=14.00` and `climb.0.stock=cobblestone ×104` —
  solid ground, clear ceiling, block in hand, no gain.

- **After falling into a cave, look for lava instead of climbing back to the surveyed pool.** The
  rung's claim is "fetch lava and cast obsidian", not "use *this* pool", and a body that just fell
  through a cave roof is standing in a cave — which at that depth is where lava is. It now searches
  24 blocks for any source and walks to it (`tunnel.otherPool`), keeping the climb-back only as a
  fallback. Cheaper than towering twelve blocks up a shaft that has already refused twice, and it is
  what a player who fell in would do.

- **`MISS` is not an obstruction.** The blocked-line guard added above did its job on its first
  outing — it refused to pour — but it named the wrong thing, because the evidence beside it was
  `cast.range=12.12, cast.picks=MISS`. Nothing was in the way: **the target was eight blocks past
  the end of a five-block ray.** `Goal.Near` reporting done is not the same as being in reach, and
  out of range a clip returns `MISS`, which reads exactly like an obstruction that cannot be cleared.
  `approachAndPour` now checks the range it actually achieved and walks again (`CAST_APPROACHES=3`,
  `cast.tooFar` recorded) instead of handing an unreachable target to the pour.

- **The food rung failed for the first time in eighteen runs, and the retry it needed had to change
  the question.** `方圆 96 格内没有掉落食物的动物 —— 附近只有 [minecraft:cat, minecraft:frog]`.
  Not a loaded-chunk problem: the rung already pins seven chunks and waits before scanning. Where it
  STARTS is wherever the stone rung left the body — the far end of whatever cobblestone that rung
  had to walk to — so seventeen runs began near cows and the eighteenth began in swamp.

  Re-scanning in place would ask the identical question and get the identical answer, the mistake
  `walkToColumn` already made once. It now walks back to spawn — the one cell this seed has an animal
  claim about — and looks again, and only then is "no animals" a statement about the world.

- **The floor regressed, twice in five runs, and both times for "nowhere to put a station".** IRON is
  gating and PORTAL_KIT is the floor, so `wd.journey99Verdict` correctly went red. Two different
  reports, one situation:

  - `furnace.craftError=需要工作台（背包里有，但脚边没有可放置的空位——先清出一格）`, reached with
    `exit.gained=2/22` — the iron shaft's exit had stalled and left the body at the bottom.
  - `smelt.lastError=需要熔炉（背包里没有可放置的熔炉）` reached with `furnace.carried=true,
    furnace.after=1` — **the furnace plainly in the bag.**

  The second message names the wrong cause. `PlaceNearby.place` holds the item through `holdItem`,
  which searches all 36 slots and found it; what it could not find was a cell to put it in. The body
  was standing on top of the one-wide pillar it had just towered out of the shaft on — air on every
  side, air under every side. Same family as `holdPlaceable`'s "out of blocks?" while carrying 110
  cobblestone, and logged in `TODO.md` as an engine-side diagnostic defect rather than fixed here.

  Two test-side fixes. **The smelt path never asked for room at all** — `makeRoomForAStation` was
  called only from `ensureCraftingTable` — so it now does. And that helper had exactly one remedy,
  walking, which is right for a pillar top and useless at the bottom of a one-wide shaft, where
  every leg ends where it began (`station.noGround` three times). It now falls back to **cutting a
  niche**: a solid side cell over a solid floor becomes an empty supported cell the moment it is
  mined, which is precisely what the driver's own error asks for (`先清出一格`).

  Note this was exposure, not a new bug: `RAW_IRON_TO_MINE = bill + 1` makes the rung work more
  veins, which puts the body in a shaft at craft time more often.

- **A pour down a blocked line is a successful pour into the wrong cell.** The cast aims at the bed
  *under* the water, because the fluid lands in the cell in front of whatever face the ray hits. A
  run chose `-15,62,42`, aimed at `-15,61,42`, and the pick answered **`-15,62,42`** — the water cell
  itself, because it held **seagrass**. Seagrass has no collision but it does have a
  `Block.OUTLINE` shape, and that is the shape a bucket's own clip uses.

  So the pour reported `CONSUME`, the bucket emptied, and obsidian appeared at `-15,62,41`: one cell
  short, cast against the near face of the plant. The rung asserts on the cell it *named*, so it
  correctly went red — but the failure reads as "the cast does not work" when the cast worked
  perfectly, one metre away. Swamp water is full of seagrass; this is terrain the ladder meets every
  run, not an oddity.

  The tunnel has always cleared its own line — *mine whatever the ray hits first, because that IS the
  obstruction* — and the cast, which holds exactly one bucket of lava and gets no second try, never
  did. It now checks the pick against the intended bed **before** spending the bucket, clears what is
  in the way, and re-aims (`CAST_CLEARINGS=2`, `cast.blockedBy` recorded).

  **Clearing was the wrong primary fix, and the next run said so.** It fired twice on the same cell
  and the seagrass was still standing (`cast.cellAfter=seagrass`), so the third attempt poured blind
  and cast one metre short again — at `-15,62,42`, the identical coordinate, because the choice is
  deterministic. The real defect is upstream: **`shallowWaterNear` tested `getFluidState().isSource()`
  and the WATER tag, and never looked at the block.** A waterlogged seagrass answers both exactly like
  open water. It now requires the block itself to be `Blocks.WATER` — a cast target must be a cell a
  ray can *enter*, and "a water source is in it" does not say that. Same shape as the survey bug where
  water was mistaken for a floor.

  The clearing stays as a backstop for genuine mid-line obstructions, but it **no longer pours when it
  runs out**. Spending the run's only lava into whatever the ray happens to hit produces
  `casts obsidian in the chosen cell (false)` with `obsidian.anywhere` sitting one metre away — a
  failure that blames the cast for working perfectly somewhere else. It now stops and names the
  obstruction instead.

- **The exit works once the tower is handed its block.** With the per-course `holdItem`, the next
  run climbed `exit.gained=36/36` from y=27 and cast at the surface — `-6, 62, 55`, on dirt, with the
  `（在地下…）` suffix correctly absent. The same run's iron rung came home with six ingots off three
  veins. Two consecutive full-ladder greens to OBSIDIAN, `staging.calls=0`.

- **A green rung said what it did not do.** The run before that climbed **one block of thirty-six** on the
  way out and passed anyway, because it found water in the cave it was already standing in and cast
  there. The rung's own claim — obsidian, unstaged — was honestly met; the exit it was also supposed
  to exercise never happened. `recordExit` now records `exit.gained=1/36` (a fraction, not a landing
  height — `exit.toY=28` is only a shortfall if you remember `exit.rise` was 36), and the PASS note
  itself says `（在地下 y=27 浇的，没能爬回地面）` when the body never surfaced.

- **`holdPlaceable` searches nine slots; `holdItem` searches thirty-six.** That asymmetry is why the
  exit stalled: `TowerProcess` asks via `holdPlaceable`, which scans only the hotbar, so a body four
  rungs deep — hotbar full of pickaxes, a bucket, flint, food — reported `no placeable block in
  hotbar` while **carrying 110 cobblestone**. The same wrong message had already been mis-read twice
  (once as a mid-air measurement bug, once as a pathfinding limit), because "out of blocks?" is a
  confident guess and the body always had blocks.

  Scripted around rather than widened: each course now `holdItem`s the pillar block before the tower
  asks, so `isSupport(main)` hits immediately. Whether `holdPlaceable` should search the whole
  inventory is a separate decision — a client body would yank items into a human's hand — and is
  logged in `TODO.md`.

- **A surveyed landmark that is never baked is not a landmark.** `JourneyRoute.thirdIron` and
  `thirdIronDescent` sat at `UNSURVEYED` while recon printed real coordinates for them on *every*
  run — `10,58,82` and `10,65,82`, 59 blocks out. The iron rung's vein loop was written to work
  veins until the portal kit's bill is paid; with only two veins baked it could not, so a run whose
  first vein lost its drops (`vein1.raw_iron=0` with `onGround=2`) banked three ingots and the
  failure surfaced a rung later as `缺 1 个 iron_ingot`. Both are baked now and checked by recon's
  staleness guard like every other landmark.

- **Mine one more ore than the bill.** The vein loop stopped at `IRON_INGOTS_THE_KIT_COSTS` raw ore,
  which assumes a furnace load returns its input — and one already had not: `部分完成：只炼出
  5/6（燃料耗尽）`. Mining exactly the bill means arriving one ingot under it whenever the coal runs
  out first, four rungs deep. It now targets `RAW_IRON_TO_MINE = bill + 1`.

- **Two veins overwrote each other's pickup evidence.** `collectByHand` recorded `pickup.walks` /
  `pickup.target` under fixed keys, and evidence entries overwrite by name, so a run that collected
  at two veins kept only the second's. The surviving reading was actively misleading: `vein1.raw_iron
  =0, vein1.raw_iron.onGround=2` — two ingots' worth lying where the body had just been — beside a
  `pickup.target` at the *other* vein, ten blocks away, which says nothing about whether vein 1's
  collect walked anywhere at all. The keys are now caller-tagged (`vein1.pickup.*`) like the shaft
  and climb keys have been from the start, and a `<tag>.pickup.left` reading taken **after** the
  collect stops separates "the walk reached it" from "it despawned while the body was at the next
  vein" — five minutes is a short life for an item and this ladder's mines are long.

- **A use uses the hand, not the bag.** Every bucket step in the obsidian rung — the fill and the
  pour — called `useItemInHand` without first bringing the bucket to the main hand. `useItemInHand`
  uses the *selected hotbar slot*, and by the time the ladder reaches the lava the body has mined a
  36-block shaft, so what is selected is a pickaxe.

  A pickaxe's `use` returns `PASS` and changes nothing. So does a bucket whose ray missed. The rung
  read `fill.result=PASS, lava_bucket=0, fill.sourceAfter=lava` **while the aim was dead on the
  source at 2.5 m** — a targeting failure's exact signature, produced by a targeting success holding
  the wrong item. The fill and the pour now go through `Avatar.holdItem` and record `fill.hand` /
  `cast.hand`, which is what separates the two afterwards.

  `wd.serverCastsObsidian` could not have caught this and now can: its body used to start with the
  bucket already selected and nothing else in the bag. It now starts the way the rung actually
  arrives — **stone pickaxe in hand, bucket behind it** — and asserts each `holdItem` before its use.

- **`Entity.pick` is the wrong instrument for predicting a use, twice over.** The obsidian rung's
  tunnel drives itself by asking what stands between the body and the lava, and it took two
  measurements to get that question asked correctly.

  First, **`pick` interpolates**: `partialTicks = 0.0F` rays from the *previous tick's* position, so
  a body at `-4,27,57` aiming at a pool 3.7 blocks away got back `57,64,56 air` — a surface cell
  sixty blocks off, through a five-block ray.

  Then, with `1.0F`, it was still wrong and now subtly: **`pick` calls `getViewYRot`, which
  `LivingEntity` overrides to return `yHeadRot`**, and `Avatar.aimAtBlock` sets `yRot`/`xRot` only.
  So the ray goes down a direction nobody aimed. Measured: the body at `-4,27,56`, the pool at
  `-6,26,54`, and hits marching *away* — `-4,28,57 → -3,28,57 → -2,27,58` — with the self-driving
  tunnel dutifully mining eight blocks in the wrong direction and then reporting, accurately, that it
  still could not see the lava.

  `Item.getPlayerPOVHitResult` — what `BucketItem` actually uses — reads `getXRot()`/`getYRot()`
  directly, so **the pour was never wrong; only the prediction was**. Both the rung and
  `wd.serverCastsObsidian` now clip exactly that way, which makes them the only readings that can
  honestly claim to say what a use will hit. That `aimAtBlock` leaves the head rotation behind is an
  engine-side finding in its own right and is logged as one rather than fixed from a test.

  **The descent underneath worked on the first try**: 36 blocks from y=63 to `shaft.landedY=27`,
  72 attempts against a cap of 128, on a column recon had chosen for it.

- **The lava survey now produces a plan instead of a coordinate, and recon says so in one second.**
  `nearestInBand` answers "where is the closest lava", which is the wrong question by exactly the
  margin that matters: seed 5471's closest pool sits under the swamp's water table, and **all 280
  columns within eight blocks of it** were rejected for having fluid in the twelve blocks below their
  own surface. A pool you cannot sink a shaft beside is a coordinate. The ore landmarks learned this
  when the nearest iron turned out to be under a pond — `nearestUnderDryGround` exists for it — and
  lava was surveyed without it.

  Recon now enumerates the nearest **distinct** pools (hits within 16 blocks folded together, so a
  lava lake is one candidate and not two hundred) and takes the first one the rung's own column test
  accepts: `(-6, 26, 54)`, 82 blocks out, dig column two cells off. Every rejected pool's tally is
  recorded. The candidate list also contains a pool at **y=63 — the surface** — which the old band
  ceiling of 50 excluded by construction.

  **Where this check runs is half the fix.** The obsidian rung is the last rung, so learning there
  that the terrain will not take a shaft costs a full run of everything below it — twenty-five
  minutes, once per guess. Recon reads the same fact at minute one, and it was that tally which
  identified the *rule* as the broken thing rather than the terrain: 280 candidates, 280 rejections,
  all one reason. A rule nothing can satisfy is not a strict rule.

  The dryness rule itself was rebalanced twice on that evidence and now applies only at the shaft's
  two ends — the mouth, where a floating body never falls into its own hole, and the landing, so the
  shaft ends on ground beside the pool rather than in the water sitting on it. What happens in
  between is the descent's problem, and the descent now reports floating in one line.

- **"Climb back to the surface" was climbing back to wherever the body had been standing.** Every
  mining rung records a `surfaceY` on arrival and climbs out to it afterwards, and that number was
  `player().blockPosition().getY()` — which is the surface only if the rung below left the body on
  the surface, and mining rungs do not.

  Measured, and it is a rung failing two rungs later. The portal kit walked to its gravel column
  **from the bottom of the iron rung's shaft**, read `surfaceY = 43`, dug, and then climbed
  *perfectly* back out: `exit.rise = 4 block(s)`, `exit.toY = 47`, goal met, rung PASS. The real
  surface was around 60. The obsidian rung then began fourteen blocks underground, could not route
  84 blocks to the lava, and reported that as a walking failure — with the walker's own
  `no progress for 1200 ticks` three times over. **A rung that climbs out to a number nobody checked
  has not climbed out.** `surfaceY` now comes from the heightmap, which answers the question that was
  actually being asked and does not care where the body is.

  The obsidian rung additionally climbs to daylight before it sets off, whatever the rung below left
  behind — the fix above removes the cause, and this stops the same shape of mistake being diagnosed
  here a second time.

  This is also the first payoff of the ascent fix in the same release: the climb's own record now
  reads `climb.3.stalled=stuck (no Y gain — out of blocks?)` beside `climb.3.stock=cobblestone ×192`,
  which is what made it obvious that the builder's guess was wrong and the *target* was.

- **The hunt now ends where it began, and says so when it cannot.** The food rung is the only one
  that goes where the TERRAIN says rather than where the route says — it follows an animal, and seed
  5471's swamp puts the nearest cow tens of blocks off in a direction nothing else on the ladder
  uses. Every rung above then started from wherever the chase ended. Measured: the iron rung reported
  "cannot reach the descent point" from `4,65,114`, **88 blocks** away, with the walker's own verdict
  `no route progress after 5 consecutive searches — goal unreachable from here`. That is not the iron
  rung's failure and should not be reported as one.

  It now walks back to world spawn — the anchor every surveyed landmark was measured from, and the
  one place the ladder knows is connected to its own route. **Best-effort and loud**: a body that got
  its food has climbed this rung whether or not it found its way home, so a failed return records
  `food.strandedAt` rather than failing FOOD, which is what lets the next rung's failure be traced to
  this one instead of investigated on its own terms.

- **A shaft column has to be dry, and a floating shaft now says so.** The obsidian rung picks its
  descent column at runtime rather than from a surveyed constant, and the first run that did so got
  everything else right — stepped off the lava's own column when it found itself standing on it,
  landed on a checked column two cells away, scaled its attempt cap to the 34-block descent — and
  then **floated**. The column was under a swamp pond, `supportUnder` answered `minecraft:water`
  122 times running, and the rung reported *"the block broke but the body did not sink"* about a body
  that was swimming.

  Two changes, and the first is the real one. `pickDigColumn` now requires
  `JourneyRoute.dryColumn` — **the ladder's own definition of dry**, the same one every ore landmark
  is surveyed against, rather than a second definition written in a second place — and rings out to
  eight cells because dryness is a far stronger filter than the geometry was. And `descendByMining`
  now distinguishes *"the floor is gone and the body is about to fall"* from *"the body is in a
  fluid"*: the first is worth a settle, the second is worth one line, because no number of settles
  fixes floating. It cost 7 000 ticks to learn nothing.

- **The wood bill is eight logs, and the wood rung will visit up to four trunks to pay it.** Five
  was the bill through the floor with **two** crafting-table remakes costed in — a table (4 planks),
  sticks (2), a wooden pickaxe (3), two replacements (8), seventeen of the twenty planks five logs
  give. A run then felled exactly five, so the top-up leg never fired, needed a **third** remake, and
  died `缺 1 个 oak_log` holding 3 planks. The arithmetic was right and the margin was zero: the
  recipes are fixed and **the table tax is what varies**, so the slack has to be sized against the
  tax rather than against the recipes.

  The top-up also stopped being a single extra tree. It now keeps going until the bill is paid or it
  runs out of trunks: the surveyed second tree first, then the nearest trunk of the **same species**
  at least 8 blocks off — far enough to be a different tree rather than the crown of the one just
  felled, which is still standing, still made of logs, and still out of a non-climbing body's reach.

- **A retry that changes nothing is not a retry — `walkToColumn` now breaks a wedged leg in half.**
  The re-plan this helper does after a short arrival assumes each attempt starts somewhere better,
  which is true when the walker stopped early and false when it is stuck. Measured: the iron rung
  ended a leg at `78,63,96` with its descent column **22 blocks away**, then spent its two remaining
  attempts and four minutes issuing about ninety pathfinder searches *from that same cell*, every one
  burning its 100 000-node budget without finding a route. Three identical questions, three identical
  answers, and the rung reported "cannot reach the descent point" for a body that had never moved.

  An attempt that ends within four blocks of where it began now aims at the **midpoint** first — a
  shorter question the pathfinder may well be able to answer — and then resumes the original leg. It
  is what a player does when a route will not come, and it needs nothing from the engine. The leg
  also records `<what>.goto.N` (the walker's own `endReason`/`lastError`) on every failed attempt,
  because ninety searches left no record of *why* beyond their own search-begin lines, and a wedge
  and a slow crossing read identically without it.

- **The lava search was horizontal, so it answered a depth question with a width answer — and
  ROADMAP N4 paid double for two runs.** `LAVA_SEARCH_RADIUS` bounds dx and dz, never y, so at 48 it
  was reporting "the nearest lava inside a 97-block-wide box" as though it were the nearest lava.
  Measured: at 48 the survey said `(84, -14, 47)`, a 77-block descent, and that number was written
  into the roadmap, the stage javadoc and this changelog as *the seed's terrain*. At 80 it says
  `(68, 27, -1)` — thirty-six blocks down, 61 out in z, never a candidate before. Both answers are
  correct; only one is useful; and **nothing in the first answer hinted the second existed**, which
  is the argument for a search that reaches past the first thing it can find.

  `LAVA_SEARCH_TOP` went 50 → 90 in the same change and moved nothing on this seed, because both
  pools are underground. It was still wrong: 50 sits below this swamp's own y≈63 surface, so a
  surface lava lake could not have been reported however close it was, and a missing answer and an
  excluded one look identical from the outside.

- **A scripted shaft's attempt cap no longer has to guess how deep it is going.** `MAX_SHAFT_BLOCKS`
  (60) and `MAX_CLIMB_STEPS` (40) were sized against the deepest hole the ladder dug at the time —
  eleven blocks — and a cap that does not know its own distance reports *"the block broke but the
  body did not sink"* for a shaft that was merely longer than the number somebody typed. That
  sentence names a driver bug and means a budget, and telling the two apart costs a whole run. Both
  are now floors under a per-block figure: three attempts per block down, two courses per block up.

- **A deep climb was asking for a block it was not carrying.** `ascendByTowering` hard-coded
  `minecraft:cobblestone`, which is right for exactly as long as every shaft stops above y=0. Below
  that the spoil is cobbled deepslate, and `TowerProcess` asked for cobblestone reports **"stuck (no
  Y gain — out of blocks?)"** with a full inventory — a message that names the wrong problem so
  convincingly that the first reading is always "the builder is broken". It now pillars with
  whichever of the shaft's own spoil the body holds most of, re-read every course, because a deep
  climb crosses the boundary where the deepslate runs out and the stone above takes over.


### Fixed
- **One tree is not one tree's worth of wood, and the wood rung was sized against the wrong bill.**
  The assertion asked for three logs because three is what the tool rung costs. It is not what the
  LADDER costs: every 3×3 craft that finds itself without a crafting table buys another one, so the
  real bill through the floor is a table (4 planks), sticks (2), a wooden pickaxe (3) and a
  replacement table per craft. Measured hauls from the single surveyed tree were **4, then 3, then
  2** on consecutive runs — the body cannot climb, so it takes the trunk at eye level and leaves the
  crown — and two runs died of that arithmetic one rung apart, both reporting `缺 1 个 oak_log` while
  holding 33 cobblestone and 2 sticks. Everything the craft needed except the table. So the survey
  now finds a `secondTree` at least twelve blocks from the first, the wood rung walks to it when the
  first came up short, and the assertion states the ladder's bill rather than the next rung's. A
  shortfall now fails at the rung that under-delivered instead of two rungs later.
- **The stone rung crafted without checking it still had a table.** `ensureCraftingTable` was added
  for the furnace and portal rungs and never for this one, which is the first 3×3 craft after the
  wooden pickaxe — the craft that actually finds the table gone. It also now looks for the table
  **standing in the world** before paying four planks for a new one: a failed reclaim does not
  destroy a table, it either drops it or leaves it placed, and those two are indistinguishable from
  the inventory while calling for opposite responses. `craftingTable.standing` / `.recovered` /
  `.remade` say which happened, because "the layer below holds on to what it places" and "the ladder
  quietly re-buys it every rung" are different claims.
- **A survey invented a landmark, and the landmark check was strict about the wrong thing.** Runs on
  one seed and one build surveyed the second tree at 13 m and at 17 m. Baking the nearer answer and
  asking recon whether it still held a log came back `found Block{minecraft:air}` — **there is no
  tree there and never was**. A chunk that is present but not finished reads as terrain without its
  features, so "the search found something" is not evidence about the world; only "a later run can
  still see it" is. Two changes: the survey now forces chunks further than its widest search reaches
  rather than exactly as far, and `secondTree` is checked for **still holding a log** instead of for
  equalling a fresh survey's answer. The second is the one that generalises — what the wood rung
  needs from that constant is a tree, not the nearest tree, and equality against an unstable search
  makes the ladder's first rung a coin flip that nobody reads.
- **The crafting table now travels with the body instead of being re-bought every rung.** Walking
  back to a table left standing was the first version and it only moved the problem one rung along:
  the table stays where the last craft happened, the body walks a hundred blocks to mine iron, and
  the next rung finds `craftingTable.standing=none` and pays four planks again. Measured, that tax is
  what ended a run at PORTAL_KIT holding four iron ingots and `缺 1 个 oak_log`. `ensureCraftingTable`
  now mines the standing table back into the bag — one block-break, which is what a player does with
  their table — and the ladder stopped paying for tables at all: the run after it reported
  `craftingTable=1` at every craft and climbed to `PORTAL_KIT` with a bucket and a flint-and-steel.
- **The food rung searched three times further than it could see.** Entities exist only in loaded
  chunks, and the journey's travelling pin is two chunks — right for a walking body, wrong for a
  searching one. So a 96-block scan from a 32-block pin had two thirds of its radius empty by
  construction, and it did not report that: it reported `方圆 96 格内没有掉落食物的动物`, on a swamp
  that has cows, from a body the rung below had walked away from spawn. The rung now widens the pin
  to cover its own search radius, waits a beat for those chunks to arrive, and narrows it again in
  cleanup — widened per rung rather than for everyone, because every extra chunk is entity ticking
  the other rungs would pay for and none of them need.
- **Seven logs, and the craft was still one log short — they were the wrong species.** A swamp mixes
  oak and birch, so "walk to the second nearest tree" often means walking to the other kind. The run
  then holds a haul that reads as plenty and cannot buy anything, because `CraftProcess`'s resolver
  commits to ONE plank variant rather than treating the recipe's tag as the recipe does: measured,
  `logs=7` with `craft.lastError=缺 1 个 oak_log` and birch in the bag. Two species are two piles for
  planning purposes, and seven logs in two piles buys less than five in one. The survey now looks for
  a second tree **of the first one's kind**, and the wood rung records `logs.kinds` per species — a
  single total read as "plenty of wood" through two failures that were really "plenty of the wrong
  wood". Widening the resolver to the `planks` tag is the real fix and belongs to whoever owns the
  recipe walk; this is the scripted way round it.
- **The iron rung treated an unfillable quota as a fatal error.** The ore sweep ran under `drive`
  with a 14 000-tick budget, so a vein that ran out mid-sweep ended the rung with `await step
  exceeded within=14000 ticks` — the ore mined, the ingots never attempted, and twelve minutes of a
  fourteen-minute run spent walking. The quota is a ceiling (raising it turns the sweep into a walk,
  which is why the answer to a thin vein is a second vein), and what the rung actually requires is
  one raw iron, asserted afterwards. It is a `settle` at 6 000 now, so an exhausted vein costs the
  leg and the second vein and the smelt still get their turn.
- **A cross-country leg now re-plans instead of reporting a walk it did not finish.**
  `IntentProcess` reports its goal reached for a partial path, which inside the collect sweep had
  already cost a fix; over open ground it is worse. Measured on the iron rung: a leg returned cleanly
  with the body **88 blocks** from the column it was sent to, and the rung reported "cannot reach the
  descent point" for what was really "the walker stopped early and nobody asked it to continue".
  Legs now re-plan from wherever they actually stopped, bounded at three tries, and record
  `<what>.walkAttempts` so a leg that quietly needs three every run stays visible.
- **The crafting table is picked up immediately after each craft, not looked for at the next one.**
  Searching for it later is a race the body always wins: once the iron rung began working two and
  three veins, the next craft was a hundred blocks and several shafts away, `craftingTable.standing`
  came back `none`, and the run bought a table it could not afford — `缺 1 个 oak_log` with five iron
  ingots in the bag. Widening the search only moves where it loses. The reclaim now happens where the
  cost is fixed, one block away and one tick after the craft, recorded as
  `craftingTable.tookItAlong`. The run after it climbed to PORTAL_KIT and the iron rung finished in
  4 628 ticks against 10 000–19 000 before, because a rung that is not re-buying a table is not
  walking back for wood either.
- **The iron rung digs until the bill is paid, not a fixed number of veins.** One vein was never
  enough on this seed and two turned out not to be either: a run took `vein1.raw_iron=0` and
  `vein2.raw_iron=3`, smelted three, and PORTAL_KIT failed on `缺 1 个 iron_ingot` holding a bucket
  and six flint it did not need. Veins here run one to three ore, so "how many veins" has no stable
  answer and "enough ore" does. The rung now works surveyed veins in order until it has what the kit
  costs or runs out, recording `iron.veinsWorked`. Two corrections came with it: the third vein must
  be clear of **every** earlier one — avoiding only the second returned `(83,59,75)`, the FIRST
  vein's own coordinate, which would have sunk a second shaft into a hole already mined out — and it
  is searched at 80 blocks rather than 48, because at 48 it was `NOT_FOUND`, which is a fact about
  the search and not about the seed. The first two keep their old radius on purpose: they are baked
  constants recon checks, and widening their search could move them.
- **The flint half was never the problem.** Worth recording because the odds invite the assumption:
  gravel gives flint one time in ten, so a rung that ends with no flint-and-steel looks like a
  probability problem. Measured, it is not — `gravel.collected=65`, `flint=6`. Both failures of that
  rung were iron: the bucket costs three ingots and the flint-and-steel costs the fourth.
- **A station needs ground, not just space — and the ladder's own exit leaves it with neither.**
  Climbing out of a shaft towers a one-wide pillar up the inside of it, so the body finishes standing
  on a column with air on all four sides *and air under all four sides*: plenty of room, nowhere to
  put anything. A first attempt at this checked only that a neighbouring cell was empty, reported
  "already room", and the craft failed anyway with the same `脚边没有可放置的空位` — which promptly
  dropped a run back to WOOD_TOOLS and was caught by the freshly-raised floor within one run of
  raising it. The test is now "empty **with something under it**", and the remedy is to step off the
  pillar rather than to dig, bounded at three short legs and recorded as `station.steppingOff` /
  `station.noGround`.
- **A craft needs somewhere to put its table.** The stone rung crafted at the bottom of the shaft it
  had just dug and failed with `需要工作台（背包里有，但脚边没有可放置的空位——先清出一格）` — a
  table in the bag and no free cell to stand it in, because a one-wide shaft has none. It **passed
  one run and failed the next on identical code**, since whether the last course leaves a usable cell
  depends on how the shaft happened to end; that reads as flakiness and is not. The rung now climbs
  out first and crafts on the grass, which is what a player does and needs nothing from the shaft's
  shape. The station searches are also flat rather than cubic — wide in XZ, a few blocks in Y —
  because a station sits on ground the body left a rung ago: a table standing at x=84 was invisible
  to a radius-6 search, so the furnace rung bought another one and ran the ladder out of wood.
- **The journey's exit from its own shaft was a search, and searches do not climb.** Every mining
  rung now digs (honest mining leaves no other way to reach buried stone), so every mining rung has
  to get back out. That exit was handed to the walker as `Goal.YLevel(surfaceY)` on the strength of
  `wd.serverPillarsOutOfAPit`, which leaves a four-deep arena pit in 46 ticks. In the field it
  bought **one block in 6 000 ticks** — `exit.fromY=54 → exit.toY=55` — and the food rung then spent
  its entire 8 000-tick budget re-searching a route out of the hole from `71,55,74`. The run fell
  back to `STONE_TOOLS`, four rungs below the floor.

  Two things differ between the arena and the field, and only one of them was depth. A rung that
  mines sideways at the bottom of its shaft ends up **under its own ceiling**, and `TowerProcess`
  cannot break — under a roof it jumps into rock and reports `stuck (no Y gain)`, which reads like a
  missing capability and is really a missing step in the plan. The ascent is now spelled out the
  way the descent already was: clear `feet+2` if it is solid, tower one course, repeat, with
  `climb.<n>` evidence per course and the builder's own `lastError` recorded if a course with a
  clear ceiling gains nothing.

  The ceiling was not the last step missing. `TowerProcess` waits for `onGround` before it jumps
  and its stuck counter starts at tick zero, so a body still settling out of the mine that preceded
  it spent all sixty ticks of that patience falling and reported `stuck (no Y gain — out of blocks?)`
  while holding thirty cobblestone. Landing first — the same non-steering `HoldStill` the descent
  uses — is what made the exit work: measured `exit.fromY=57 → exit.toY=63`, six courses, six
  cobblestone, alternating "mine the dirt overhead" and "tower into the gap", where the previous
  build managed `53 → 53`. The walker keeps a recorded fallback for what the tower cannot do, and
  `exit.walkerFallback` says when it was needed, because two ways up with no note of which carried
  the body is how a capability quietly stops being tested.

  The stone rung's quota went 20 → 32 with it. The bill was longer than it looked: a stone pickaxe
  (3) plus one cobblestone per course of the exit (the shaft is nine deep) plus the furnace (8) —
  so a rung that came back with nineteen was paying the exit out of the furnace's share.
  `wd.serverTowersOutOfADeepShaft` pins the shape in an arena: nine deep, one wide, with the
  sideways alcove that puts the roof there.

- **The iron rung now walks onto its own drops instead of asking the sweep twice.** Measured:
  `broke 2/8`, `raw_iron=0`, `raw_iron.onGround=2`, `collect timed out after 240 ticks`, with the
  body five blocks away from ore it had broken itself. The sweep's failure is `MineProcess`'s own
  business and has its own sensor (`wd.serverMineHarvestBuried`); the rung, meanwhile, knows exactly
  what it broke, so it reads the item's position out of the world and walks there — three legs, each
  a best-effort settle, with a beat on the spot afterwards because a fresh drop carries a 10-tick
  pickup delay. `pickup.walks` records how many legs it took, which is the number that says whether
  the sweep is getting better or worse.

- **A 3×3 craft can eat the run's only crafting table.** `CraftProcess` places a table when none is
  in reach and reclaims it on the way out, and the reclaim is best-effort by design — a craft is
  never failed over cleanup. The journey's stone rung crafts at the bottom of its own shaft, so the
  run climbed out with `craftingTable=0`, and the furnace rung then sat on 24 cobblestone and
  crafted nothing while reporting only `furnace=0`. The ladder now re-crafts a table before any 3×3
  craft that needs one (`craftingTable.remade` says when it had to) and the furnace rung records
  `craftingTable` and `craft.lastError`, because "the station, the grid, or the process" are three
  different bugs and the furnace count alone separates none of them.

- **The scripted shaft mistook groundwater for its own floor.** `supportUnder` picked the cell
  holding the body up with `!isAir()`, and water is neither air nor a floor. Measured: the shaft
  broke its centre cell, swamp groundwater filled the hole, and from the third pass on the digger
  answered "the support is the water" for twenty-eight consecutive passes — mining a fluid is a
  no-op — while the corner cell actually carrying the body was never touched. It printed "the block
  broke but the body did not sink", which is true and points at the walker. `blocksMotion()` on both
  the picker and the already-open branch turned the same column from FAIL (thirty passes, zero
  descent) into PASS (603 ticks, 21 cobblestone).

  Why the water got in is a second bug, in the survey: `dryCross` certified the column and its four
  cardinals, which is the footprint `DescendProcess` cuts a staircase through. A scripted shaft is
  not a staircase — a player box is 0.6 wide, so a body near a cell edge is held up by a
  *neighbouring* cell and the digger breaks that one too, making the hole up to 2×2 whose walls are
  the ring a cross never looks at. Widened to 5×5; on seed 5471 that moves `firstStone` from
  `(72,59,74)` to `(83,59,76)`.

- **The collect sweep threw away drops it had never walked to.** `Walker.Step.ARRIVED` does not
  mean the goal was reached — it means the path the walker computed ran out, and when A* cannot
  reach the goal it returns a best-effort partial path. `Goal.Block.reached` is an exact cell
  match, so the two disagree freely: measured, `retired 2 drop(s): 0 unpathable + 2
  arrived-but-short` with the body 4.1 and 6.4 blocks from the items it had just given up on.

  An ARRIVED that is not at the goal cell now re-plans, up to three times, before the drop is
  retired. Re-planning is not superstition here: a mine changes the world while it runs — its own
  shaft opens routes that did not exist when the first search failed. The journey's iron rung left
  four drops on the ground under the old behaviour.

- **The server avatar mined through solid rock, and that is why a playthrough could not gather
  buried ore.** `Level#destroyBlock` has no reach check and no visibility check, so the avatar
  broke whatever it aimed at, at any distance, through any amount of stone. The consequence is not
  cosmetic: an ore mined under an intact floor drops its item into a **sealed 1×1×1 pocket**, and
  nothing can ever collect it.

  That pocket is what `wd.serverMineHarvestBuried` had been failing on all along, and two rounds of
  work went into the wrong files first — the walker, then the collect sweep — because the verdict
  said "the drop was not collected" and nobody had looked at the drop's surroundings. Adding two
  fields to the diagnostic ended it in one run: `above=Block{minecraft:dirt}, openSides=0`.

  `ServerPlayerAvatar.breakHold` now refuses a target that is either walled in on all six faces or
  beyond the player's own `blockInteractionRange`. It deliberately does not raycast — vanilla's
  server does not either; it trusts the client's aim and checks distance — so exposure plus
  distance is the honest server-side form of "a client could have aimed at this".
  `wd.serverBreakNeedsReach` pins the contract with three targets at once (sealed, far, adjacent)
  so a fix cannot trade one for another.

  **Blast radius, stated rather than hidden: two scenes were green because of this bug.**
  `wd.buriedOre` mined through the ore's overburden, and `wd.serverEscapeSealedShelter` carved at
  the exit block three courses above the body instead of at the next block up.

- **The miner now peels its own overburden instead of swinging at what it cannot hit.**
  `MineProcess` aimed at the target it wanted; with the reach gate in place that is a swing that
  can never land, and the no-progress watchdog ends up reporting "no reachable target" about ore
  the bot is standing on top of. `firstBreakableToward` walks the segment from the eye to the
  target and returns the first solid block along it the avatar can actually break, and that block
  becomes a **clearing** target — machinery that already existed for leaves occluding a log, so it
  does not count toward the quota, does not seed COLLECT, and re-SEARCHes on completion so the
  newly exposed block is picked up normally. One block per pass, which is what a player does, and
  which also keeps every drop at the bottom of a hole the body can walk into.

  `wd.buriedOre` is **required again**, and `wd.serverMineHarvestBuried` — optional and red by
  design since it was written, the scene that made the journey's IRON rung a coin flip — is
  **required for the first time**. Its diagnosis changed completely on the way: the drops were never
  at the bottom of a hole the walker refused to enter, they were sealed inside rock the avatar had
  no business mining through.

  A target that is **exposed and still unbreakable** is out of range, and range does not improve by
  standing still — so it is retired immediately instead of after the no-progress watchdog's hundred
  ticks. That distinction is load-bearing in both directions. Without the retirement the journey's
  wood rung went from six logs to **zero**: the reach gate had also revealed that the bot harvested
  canopy logs five blocks above its own head, and waiting a hundred ticks per unreachable log ate
  the whole budget before it ever tried the trunk. Without the *exposure* half of the test, the
  deepest of the three ores in `wd.serverMineHarvestBuried` was retired before the peel could
  uncover it. Buried is temporary; far is not.

  `wd.serverEscapeSealedShelter` stays optional. It runs a different digger, which still aims at
  the exit; teaching that one the same lesson is what promotes it back, and the reason is recorded
  at its registration rather than here.

- **One unreachable break cell pinned the whole sweep, on the branch the skip list did not
  cover.** `findCollectGoal` retires drops the collect walker gave up on, and that guard was on the
  item scan only. Once every visible drop was retired the search fell through to the
  `recentBreaks` fallback, which happily handed back a break cell at the bottom of a hole the body
  cannot enter — forever, because the pop test is "within 1.5 blocks" and it never gets there.
  `wd.serverMineHarvestBuried` spent its entire 240-tick budget walking toward a cell already known
  to be dead, then reported `collect timed out`. It now finishes in 121 ticks with
  `collect swept everything it could reach`, which is the truth.

  The same verdict now names what the sweep was doing when it stopped, split by cause:
  `retired 2 drop(s): 0 unpathable + 2 arrived-but-short`. That distinction is the whole diagnosis
  — "the walker will not path there" and "the walker says it has arrived and the item is four
  blocks away" are opposite bugs in different files, and the drop count alone sent two
  investigations to the wrong one.

- **A `ServerWorldDriver` that had ever run a process could never be given another order.**
  `tick()` branches on `process` before it looks at `mineTarget`, and neither `mine()` nor
  `gotoGoal()` cleared it — only `runProcess` cleared the other side. So on any driver with a
  process in its history, every later `mine`/`gotoGoal` was **silently ignored** and the stale
  process ran again instead.

  Nothing reported an error, which is what made it expensive. The old process reached its
  already-satisfied goal, the driver finished, and the caller read that as the mine completing.
  The journey's iron rung scripted a shaft — break the block below, fall in, repeat — and produced
  twelve legs of `shaft.N.broke=grass_block`: the same untouched ground, twelve times, reported as
  twelve successful mines. Two wrong diagnoses came out of that before the evidence line that
  compares the block *after* the mine to the block before it.

  `wd.serverSelfShaftDescends` is the regression test, and its first version would not have caught
  this: a fresh driver mines before it has ever held a process, which is the one ordering where the
  bug cannot appear. It now mines a second course **after** a process has owned the driver.
  Verified by reverting the fix — the scene fails with `deeper=Block{minecraft:stone}`.

- **`MineProcess` mined, then walked away from the harvest — twice over.** Two independent
  ways COLLECT could end with the drops still on the ground, both of which made a mine report
  success while banking nothing.

  *The pickup delay.* A block broken at arm's length drops its item **at the miner's feet**
  with vanilla's 10-tick pickup delay. `findCollectGoal` skips delayed items (walking to one is
  pointless) and its `recentBreaks` fallback pops the break cell the bot is already standing
  on — so one tick after the break both correctly answer "no goal", and COLLECT read that as
  "nothing left" and finished. Mining one iron ore took 24 ticks and banked nothing: ore gone,
  drop on the floor, no error. COLLECT now stands still while a drop inside 2 blocks is still
  counting down, bounded at 20 ticks so the pathological case (nothing is ticking the entity,
  as in every scene that spins its avatar inside one server tick) cannot hang.

  *The short quota.* Asking for four ores where the vein holds two returned straight out of
  SEARCH — `st.mine.reset(); return true` — so COLLECT never ran at all and both drops were
  abandoned. "I got nothing" where the truth was "I got two". The quota now decides how long to
  keep looking and never who owns the harvest; the short-quota `lastError` still reaches the
  caller, because `reset()` preserves it.

  *The arrival that was not one.* The sweep walked to a goal looser than vanilla's pickup reach
  and then stood on it. Measured: `lastStep=ARRIVED`, a drop **1.6 blocks away**, and the whole
  240-tick collect budget burned without touching it — the magnet reaches about 1.4 blocks
  (bounding box inflated 1.0), so "adjacent to the drop's cell" is not close enough. The goal is
  the drop's own cell again, which is what a player walks onto.

  *The dead goal that shadowed the live ones.* `findCollectGoal` returns the NEAREST drop and the
  walker's verdict was discarded, so one unreachable drop was re-pathed every tick until the cap
  while every reachable drop behind it went uncollected. `Walker.Step.FAILED`, and "ARRIVED but
  the item is still lying there", now both retire that drop and let the next one through.

  **Why nothing caught any of them:** every mine scene asserted that the target block stopped
  being there. `wd.serverMineHarvest` is the new sensor that owns the other half — PASS means
  items that did not exist before are in the inventory. It runs over **real server ticks** rather
  than an in-body `tickAll()` spin (a drop cannot count down its delay in a level that is not
  ticking), it starts the pickaxe in the bag with dirt in the hotbar so `holdPlaceable` grabs the
  dirt exactly as it does in the field, and it asks for four ores where three exist and spread
  over a circuit, so the short quota, the delay and the sweep are all on its path.

  `wd.serverMineHarvestBuried` is the same circuit with two of the three ores under the floor,
  and it is **optional and red**: a drop that falls to the bottom of a hole the avatar dug from
  arm's length is still not retrievable. It is shipped red rather than softened because softening
  it would encode the cliff as the requirement — the mistake `wd.serverSmeltStationOpens` was
  renamed for.

- **`MineProcess` states a terminal verdict.** `BunkerProcess` and `IntentProcess` already stamp
  `goalReached` / `endReason` at every terminal exit, for the reason gap#68-R2 names: a run that
  ends `active:false` with no `lastError` is indistinguishable from one that succeeded. Mine was
  the outlier and is the verb where it hurts most, because breaking a block and acquiring it are
  two different events and only the first was ever reported. The verdict now carries the count
  that was missing — `collect timed out after 240 ticks (broke 3/4, left 2 drop(s) on the
  ground)` — which is one line saying what previously took three playthrough runs to establish.

- **The server avatar can earn advancements — on Fabric.** Two halves were missing and both
  looked free. A body that was never placed through `PlayerList` has an `inventoryMenu` with no
  listeners at all, so `ServerPlayerAvatar` now calls vanilla's own `initInventoryMenu()`; and a
  real `ServerPlayer` calls `containerMenu.broadcastChanges()` once per tick from `doTick`, which
  this avatar's `Player`-shaped tick never did. Neither is about packets — the connection
  discards those — but `ServerPlayer`'s `ContainerListener` fires
  `CriteriaTriggers.INVENTORY_CHANGED` from `slotChanged`, and that trigger is what awards
  `story/root`, `story/mine_stone`, `story/upgrade_tools` and `story/smelt_iron`. Without it a
  server-driven agent could craft a table, mine cobblestone, upgrade its pickaxe and smelt iron
  and earn **nothing**. Surfaced by the journey ladder, which records an advancement per rung and
  reported `not-earned` for every one.

  **`wd.serverAvatarEarnsAdvancement` is green on Fabric and red on NeoForge**, from the same
  common constructor and the same common tick over NeoForge's own `FakePlayer`. The divergence is
  not yet explained and is shipped as a named optional row rather than an assertion nobody sees,
  because a driver whose job is to report a modpack's progression to an agent must not silently
  award nothing on one loader. The scene drives a `LookProcess` for a few ticks purely because a
  registered driver with nothing to do is not ticked at all — which is itself worth knowing: the
  inventory broadcast rides the body's tick, so an item handed to an idle body earns nothing until
  something next runs.

- **A server-side agent could not acquire anything it mined.** Two independent gaps stacked,
  and either alone was enough to make gathering impossible. `ServerPlayerAvatar.breakHold`
  called `Level#destroyBlock(pos, false, fp)` at both call sites — an unexplained literal,
  almost certainly left from when the avatar only ever dug *through* terrain to open a path —
  so a broken block produced no `ItemEntity` at all. And `mirrorPlayerTick()` never ran the
  entity-touch loop from `Player.aiStep`, which is the only route by which
  `ItemEntity.playerTouch` hands a stack to a player, so even a drop that existed could not be
  picked up. Both are now faithful: blocks drop their harvest, and the touch loop is mirrored
  including vanilla's own rate limit on experience orbs (one random orb per tick, the rest
  touched immediately).

  **How this survived 222 green scenes:** none of them ever asserted that an item reached an
  inventory. The one named for it, `wd.serverCombatCollectDrops`, passes when the bot ends
  within two blocks of a drop — `pickedUp || distToDrop <= 2.0` — so it measured that the bot
  walks back to where a drop would be, never that it collects one. The gap surfaced the first
  time something asked directly: the wood rung of the new journey ladder felled its tree,
  watched `MineProcess` meet its quota and enter COLLECT, and ended with zero logs.

  **Blast radius, stated because it is behavioural and not merely cosmetic:** every arena
  where the avatar digs now spawns item entities, and the avatar may finish a scene holding
  what it dug. `holdPlaceable()` selects the first placeable in the hotbar, so a bot that has
  just picked up the dirt it tunnelled through can now *place* where it previously had nothing
  to place. This is what the client path has always done; scenes written against the old
  silent-break avatar are the ones that move.

- **The server avatar picks a tool again.** `ServerPlayerAvatar.selectTool` was a no-op —
  "arena breaks with hand/held; best-tool optional" — and until blocks started dropping their
  harvest it genuinely was optional, because nothing the avatar broke produced anything. It now
  ranks by the client's own rule (correct-for-drops beats fast; equal correctness, faster
  wins), searches the bag as well as the hotbar, and swaps a winner into the selected slot.

  This entry first claimed the fix was load-bearing for *drops*, on the reasoning that
  `Level#destroyBlock` gates them on `canHarvestBlock`. It does not: it passes
  `Block.dropResources` a literal `ItemStack.EMPTY` and never looks at the hand. What
  `selectTool` decides here is break *speed*. The empty bag that prompted the work was a
  `MineProcess` collection bug (below), and the wrong diagnosis is recorded rather than quietly
  edited out because it cost a round of engine changes aimed at the wrong file.
  `BotInteract.selectBestToolFor` could not be reused — it takes a `Minecraft` and lives on the
  client side of the seam — so the ranking is reimplemented, minus its Efficiency lookup, which
  only reorders tools that are already correct.

- **A server-side agent can now use a station: crafting tables and furnaces open.**
  `ServerPlayerAvatar.useBlock` installs the menu the block would have opened when vanilla's own
  route declines to — which it always did, because a fake player's `openMenu` returns
  `OptionalInt.empty()`. `CraftingTableBlock` reaches its menu *only* through `openMenu`, so a
  right-click on a table did nothing and `CraftProcess` sat in `OPEN_WAIT` until it timed out.
  Both this method's comment and `CraftProcess`'s called that a "capability cliff" and left it,
  which meant **the server agent could craft only what fits the 2×2 inventory grid** — and
  every rung of a playthrough above planks (pickaxes, furnace, buckets, flint and steel) is
  3×3.

  Deliberately placed in `ServerPlayerAvatar` (common) rather than by un-overriding
  `AvatarFakePlayer.openMenu`: that class is the *Fabric* body, while NeoForge injects its own
  `FakePlayer` through `ServerAvatarBodies`. Fixing it there would have fixed one loader and
  left the other timing out. Vanilla's `initMenu` is skipped — it attaches a slot listener and
  a synchronizer, both of which exist to send packets to a screen this body does not have, and
  both are private on `ServerPlayer`. Everything that matters is server-side and untouched:
  `CraftingMenu.slotsChanged` still recomputes the result, `clicked` still moves stacks, and
  closing still returns what was left in the grid.

  Two scenes had encoded the cliff as the requirement and are inverted with it:
  `wd.serverCraftTableReclaim` used the guaranteed craft failure as its vehicle for
  reclaim-on-failure and now asserts reclaim on the success path (plus that a pickaxe was
  actually made); `wd.serverSmeltCliff` → **`wd.serverSmeltStationOpens`**, which now asserts
  the furnace opens and takes its load rather than that the process degrades gracefully. Both
  loaders' dedicated gates are GREEN after all three fixes, with the only non-canary failure
  still the known optional sensor `wd.vineOverWaterClimb`.

### Added
- **A server-agent body that JOINS the server, behind `-Dworlddriver.realPlayerBodies=true`.**
  Every gap the playthrough ladder found in the headless agent had one shape: vanilla does the
  thing inside a method a fake player never runs, and the fix was to hand-copy one more piece of
  `Player.tick()` into `ServerPlayerAvatar.mirrorPlayerTick()`. That list only grows, because it
  is a re-implementation maintained by discovering what is missing.

  A `FakePlayer` is a `ServerPlayer` that was never *placed*. `PlayerList.placeNewPlayer` is what
  attaches the inventory-menu listener that fires `INVENTORY_CHANGED`, loads the profile's
  `PlayerAdvancements` and points it at the body, puts it in `ServerLevel.players()` so the level
  keeps ticking and mobs can see it, registers it with the `ChunkMap` so it loads what it walks
  into, and fires the loader's login event that modpack mods hook. None of that is reachable by
  copying methods. `JoinedPlayerBodies` installs into the existing `ServerAvatarBodies` seam, so
  the 222 dogfood scenes and the journey ladder become an A/B harness rather than an argument.

  Measured on seed 5471, same ladder, same rung (`FURNACE`), zero staging both runs:

  | ladder evidence | fake body | joined body |
  |---|---|---|
  | `advancement.root` at WOOD_TOOLS | not-earned | **earned** |
  | `advancement.mine_stone` at STONE_TOOLS | not-earned | **earned** |
  | `advancement.upgrade_tools` at STONE_TOOLS | not-earned | **earned** |

  The hand-copied `initInventoryMenu()` was enough to make a synthetic scene
  (`wd.serverAvatarEarnsAdvancement`) pass and did nothing for the actual playthrough. Joining
  fixes it everywhere with no per-criterion work — which is the argument for the whole approach.

  It also closes the loader divergence that entry left open. `wd.serverAvatarEarnsAdvancement` was
  green on Fabric and red on NeoForge off the same `:common` constructor and the same `:common`
  tick — NeoForge's own `FakePlayer` simply would not report a criterion. Joined, it passes on both.
  The explanation is the same one line: a body that was placed does not need either loader's fake
  player to behave.

  The NeoForge join needed one thing Fabric's did not. Vanilla's path never touches
  `Connection.channel()` — every reach for the wire goes through `send`, which this class swallows —
  but NeoForge stores the connection type as a **channel attribute**, so `placeNewPlayer` died on
  `channel().attr(...)` and took the whole armed suite down to 76 executed scenes. There is no
  setter for that field and `channel()` is NeoForge's accessor rather than a vanilla method, so it
  cannot be overridden from `:common`; the connection instead registers itself on an
  `EmbeddedChannel`, whose `channelActive` is what assigns the field. The channel's tail discards
  and completes each write, because `EmbeddedChannel`'s default is to queue outbound messages
  forever — a silent leak in place of a loud crash.

  Both gates GREEN armed, with coverage identical to the unarmed baseline: Fabric 208 executed /
  20 skipped, NeoForge 209 / 19.

  One trap worth recording because it nearly got reported as a win. Scenes already dispose their
  bodies with `fp.discard()`, which is enough for a fake player and not enough for a placed one:
  `PlayerList` keeps its own list, so the first armed run logged 79 joins and 0 departures. Those
  corpses satisfied `ctx.player()`, and thirteen scenes that should have skipped ran against one —
  reading, at a glance, as "joining bought 13 scenes of coverage". It bought none; `JoinedBody`
  now leaves the player list when it is discarded, and the suite reports exactly the coverage it
  did before (208 executed / 20 skipped, GREEN).

  Off by default, and deliberately half-finished: `JoinedBody.tick()` is still a no-op because
  `ServerPlayerAvatar.step()` integrates locomotion by hand and vanilla's `aiStep` would integrate
  it a second time. The second half is teaching the driver to write inputs (`xxa`/`zza`/`jumping`)
  instead of positions. Until then a joined body has vanilla's wiring but not vanilla's tick, and
  anything derived per-tick inside `Player.tick()` — the attack-strength ticker, for one — stays
  frozen.

- **`wd.journey*` — the playthrough ladder: worlddriver asking whether its own API can finish
  the game.** Twenty rungs from an empty inventory at world spawn to a dead ender dragon, run
  as one continuous chain over one body in one world. Every other scene family asks whether a
  verb works; this one asks the question they add up to and none of them answered.

  Four rules make its results mean something. **One run**: stages are chapters sharing state
  through `JourneyLedger`, not independent tests, so `IRON` means ore this body mined with a
  pickaxe it crafted from wood it cut. **Nothing staged**: no give, no setblock, no fill, no
  teleport — and the claim is measured (`JourneyLedger.stagingCalls()`), not asserted. **Every
  step scripted** against landmarks surveyed from the fixed seed 5471 (`JourneyRoute`), so a
  failure names the driver failing to execute a correct plan rather than a planner failing to
  find one. **The frontier fails freely**: rungs ahead of the engine ship optional, and
  `JourneyLedger.FLOOR` is the single number the verdict scene ratchets against.

  Off by default — a playthrough is hours where the gates are minutes — behind
  `-Dworlddriver.journey=true` and `:fabric:runJourneyServer`, which provisions its own run
  directory, forces the seed, and **deletes the world first** (a playthrough plays the world,
  so run 2 would otherwise begin with run 1's tree already felled). `wd.journeyArmed`
  registers in every run, including the six gates, so a family that silently stopped
  registering shows up as a changed rung count in five minutes rather than hiding behind a
  journey nobody ran.

  Current height on seed 5471 (spawn is a swamp): **`FURNACE`** — recon, spawn, wood, wooden
  tools, stone tools, a hunted cow and a crafted furnace, all in one unbroken run with zero
  staging calls. The floor was ratcheted three times getting there (`WOOD_TOOLS` →
  `STONE_TOOLS` → `FOOD`), each time in the change that first ran that rung green.

  **A prerequisite is not an order.** Every rung blocks everything above it, so a rung in the
  middle of the list asserts that nothing above it can happen first — which is false for `BED`.
  A bed is a durability keystone (it moves the spawn point so a death does not undo the run) and
  nothing on the road to the dragon needs one; on this track it is doubly irrelevant because the
  body cannot die. It mattered because of terrain: seed 5471's swamp holds cows and frogs but no
  sheep, so wool means a long walk. Left in the line, "could not find a sheep" would have blocked
  iron, the portal and the whole nether. `JourneyStage.requires()` and `criticalPath()` make it a
  side rung — skipped without blocking, and not counted toward the height.

  **A surveyed coordinate is not yet a plan.** Three rungs were written as "go to where the
  resource is", and all three were wrong in the same way. `Goal.Near(target, 3)` judges 3D
  distance, so aimed at a log five blocks up it tells a bot standing at the foot of the tree it
  is four blocks short and sends it climbing, and aimed at an ore seven blocks down it reports
  "could not reach the iron" about a bot standing on top of it. Wood passed for as long as the
  pathfinder happened to pillar in time and then, unchanged, spent 1304 ticks ending nine blocks
  off. Navigation now approaches the **column** (`Goal.XZ`) on all three, and reaching up or down
  is left to the verb whose job it is.

  The iron rung needed the same correction one level further out. Seed 5471's *nearest* iron is
  under a swamp pond, and no amount of driver is going to sink a shaft through standing water —
  `DescendProcess` inspected its four cardinals and its own column, found every one wet, and
  refused, which is the correct behaviour and makes the coordinate the bug. Surveying a dry
  descent column separately then produced a point 18 blocks away, trading a flooded shaft for a
  long blind tunnel. `JourneyRoute.nearestUnderDryGround` asks the two questions together — the
  nearest ore whose own column *and its four cardinals* are dry the whole way down, which is the
  footprint a staircase occupies — and answers with an ore 26 blocks out that the shaft lands on.
  A survey that only records where things are produces plans nothing can execute.

  **`IRON` has been climbed — and is deliberately not the floor.** A run took the ore, smelted it
  and finished holding two ingots, with nothing staged. The next run of the same code failed it.
  The cause is known and has a sensor: this seed's iron is four blocks under its surface, and a
  drop at the bottom of a hole the avatar dug is not retrievable yet
  (`wd.serverMineHarvestBuried`), so the rung turns on where the drop happens to land. `FLOOR` is
  therefore `FURNACE`. A floor is a claim that a rung WORKS, not that it once worked; ratcheting
  onto a coin flip would make every later red row unreadable.

  The explicit descent step is gone with it. `DescendProcess` was the right verb on paper — its
  own javadoc argues a veteran digs a staircase rather than asking A* to price a shaft — and on
  this ground it walked the body nineteen cells sideways for one block down and reported "no safe
  descent stride (all cardinals + own column wet/hazard/unbreakable)". A staircase needs somewhere
  to step INTO and a swamp does not have it. The rung passed in spite of that step, not because of
  it; the limitation is recorded and the plan no longer depends on it.

  `wd.journey01Recon` doubles as the staleness guard — it re-derives every baked coordinate and
  fails when one moves, because a stale survey does not report itself, it reports "the bot could
  not gather wood".
- **Scenes run in a world StageWright holds still, and the two rigs that used to hold it
  still for themselves stopped doing so.** The suite pins `dayTime` to a frozen midnight and
  turns off `doDaylightCycle`, `doWeatherCycle` and `doMobSpawning`, announcing the list at
  suite start and in the results header. This suite finishes inside `dayTime`≈130 — sunrise,
  exactly where sky brightness crosses the threshold vanilla dice-rolls against to decide
  whether a sun-sensitive mob ignites — which is why `wd.serverCombat`,
  `wd.serverCombatCollectDrops` and
  `SimProbes.probeSwing` had each independently grown a defence against the sun. The first two
  now say nothing about time at all; `probeSwing` keeps its fire resistance because a caller
  that legitimately asks for `Clock.NOON` must not get a different number out of it.
  `pack.runsAtTheFrozenNight` / `pack.runsAtTheClockItAsked` assert the pin and the override
  from inside a scene rather than trusting the header that announces them.
- **`BotConfig.keepTickingUnfocused` (default true) — a driving bot no longer gets paused
  by an alt-tab.** Vanilla singleplayer pauses on lost focus; for a bot mid-task that stops
  the world partway through a goto/mine, and the `PauseScreen` it opens then sits
  *underneath* every later screen assertion. The second effect is the expensive one: one
  focus slip during a T1 run turned the 1-failure baseline into 5 unrelated-looking
  failures (`10_client` "screen should be null after close" — the pause menu was behind the
  inventory; `12_use_item`; and both mob scenes, because a paused world advances no ticks).
  Nothing in those messages mentions focus.

  Scoped like the `MouseYield` handshake it sits beside: applied only while a process owns
  the tick, with the human's own `options.pauseOnLostFocus` handed back on the falling edge
  and on client shutdown (Minecraft saves `options.txt` on close, so a force-quit mid-drive
  would otherwise persist the override into their real settings). Only the *automatic*
  focus-loss pause is suppressed — an Esc menu the human opened is never touched.
  `scripts/stagewright/t{1,2}.py` also seed `pauseOnLostFocus:false`, since the client-face
  validation scripts drive the client with no process running and the mod-side setting does
  not cover them.

### Changed
- **BREAKING (module layout): StageWright now depends on WorldDriver, not the reverse — and
  the driver's jars no longer contain it.** `:common` had
  `implementation project(':stagewright-common')` in its **main** source set, so both shipped
  jars carried 25 StageWright entries: the harness, the scene API, a JSONL results writer.
  Every other oddity in that seam was downstream of it — the bundling meant a worlddriver-only
  install had nothing to arm the harness, so the loader entrypoints forwarded server lifecycle
  in ("UNCONDITIONAL", by their own comment); but `stagewright-{fabric,neoforge}` already
  registered those same events themselves, so both paths fired and `StageWrightCommon` grew an
  `if (armed) warn` guard; and `stagewright-common` needed `ToolCatalog`, which would have been
  a cycle, so `StageWrightVerbHook` was invented to invert it.

  Reverted at the root instead. `stagewright-common` now depends on worlddriver's `:common`;
  worlddriver depends on StageWright only from its `testmod` source set, which keeps the Gradle
  task graph acyclic without needing a separate `stagewright-api` artifact.

  - Both worlddriver jars now contain **zero** stagewright entries.
  - `StageWrightVerbHook` and its `META-INF/services` file are **deleted** — StageWright calls
    `ToolCatalog.registerVerb` directly.
  - `TestRunVerb` / `TestResetVerb` / `TestInputVerbs` move from
    `net.magicterra.worlddriver.bot.stagewright` (a package in the driver's production tree,
    named after its test framework) to `net.magicterra.stagewright.verbs`. That package is now
    absent from worlddriver's main entirely, retiring the JPMS split-package hazard that forced
    the scenes into a `.scene` sub-package.
  - `WorldDriverCommon.ensureRpcUp` no longer registers `mc.test.*`. **A server without
    StageWright installed has no `mc.test.*` surface at all** — a stronger gate than the
    system property it replaces.
  - Dev runs get `modLocalRuntime project(':stagewright-<loader>')` so `dogfoodServer` /
    `stagewrightClient` / `t2Server` boot with both mods side by side, as a production install
    would. Never published, never bundled — the build-graph equivalent of `testImplementation`.

- **The StageWright orchestrators moved out of this repo; `scripts/stagewright/` now holds
  shims.** Every documented gate command is unchanged — `python scripts/stagewright/t0.py
  --loader fabric …` still works — but `t0`/`t1`/`t2`/`instrument`/`instrument_client`/`pool`
  are ~25-line delegations to a StageWright checkout, expected as a sibling directory
  (`../stagewright`, override with `STAGEWRIGHT_HOME`). What stays here is consumer data: the
  per-loader `expected-scenes-*.txt` manifests.

  The orchestrators used to derive the repo root from their own `__file__`, which after the
  move would resolve to StageWright's tree and silently drive the wrong build. They now take
  `--project-root` (falling back to `$STAGEWRIGHT_PROJECT_ROOT`, then the cwd), and the shims
  pin it to this repo so a gate can never be aimed at whatever directory you were standing in.

  Two gate defects surfaced while proving this, both of which had been reporting success
  without checking anything:
  - t0's "shipped per-loader manifests agree" compared two manifests via a helper that
    returns an empty list for a *missing* file, so `not []` passed. It is now tri-state and
    reports SKIP when the manifests are absent — which is what StageWright standing alone
    now correctly says, and worlddriver still says PASS.
  - t1/t2 resolved `TESTKIT_DIR` once at import from the pre-`--project-root` value, so
    `--project-root` moved the run dirs but left the world-template cache behind — the
    dirty-world failure mode, arriving silently. Resolved lazily now.

- **BREAKING (build): StageWright is no longer a subproject of this build — it is a
  published dependency.** `settings.gradle` no longer includes `stagewright-{api,common,
  fabric,neoforge,junit}`, `stagewright/` is deleted from this repo, and the framework
  arrives as artifacts:

  | consumed as | coordinate | why that face |
  |---|---|---|
  | testmod compile | `net.magicterra:mc_stagewright-api:<ver>:dev` | named mappings; not a `mod*` configuration, so loom must not remap it |
  | dev-run classpath | `net.magicterra:mc_stagewright-<loader>:<ver>` | a `mod*` configuration, so loom remaps intermediary → named |
  | gradle tasks | plugin `net.magicterra.stagewright:<plugin-ver>` | was `includeBuild('stagewright/gradle-plugin')` |

  Versions are `stagewright_version` / `stagewright_plugin_version` in `gradle.properties`.
  The two are separate because the plugin carries no Minecraft classpath and does not move
  with the Minecraft version.

  **Bootstrap order matters and is not discoverable from an error message.** The
  dependency runs both ways — `stagewright-common` compiles against `worlddriver-common`,
  and this repo's testmod compiles against `mc_stagewright-api` — so a fresh clone must:
  publish `worlddriver-common` first (this repo's **main** source set has never needed
  StageWright, which is what makes the cycle only apparent), then publish StageWright, then
  build here. The sequence is written out at the top of `../stagewright/build.gradle`.
  Skipping the first step fails on an unresolved `worlddriver-common:<ver>:dev`.

  Both shipped jars still contain **zero** StageWright entries, and `modLocalRuntime` is
  still never published and never bundled — only its source changed, from a project
  reference to a coordinate.

### Removed
- **BREAKING (RPC): the YAML GameTest harness is retired — `mc.test.yaml` is gone.**
  It was the driver's *second* in-game test system, living alongside StageWright's
  Scene suite, and the reason it existed had already expired: its own javadoc said
  "the same `YamlTestInterpreter` also backs the `@GameTestGenerator` hook", but
  `@GameTest` was retired in P4-final. Meanwhile it squatted on the `mc.test.*`
  namespace granted to the StageWright runtime — `ToolCatalog` carried an explicit
  "grandfathered" carve-out for it, which is a deferred conflict, not a resolved one.
  Confirmed with the owner that it had no consumers.

  Removed: `test/yaml/{YamlTestInterpreter,YamlTestLoader,YamlTestSpec,AssertKind}`,
  the `mc.test.yaml` route + hidden `ToolSchema`, `data/worlddriver/gametests/`
  (`index.txt` + `smoke_place_observe.yaml`), the `34_yaml_gametest.js` validation
  script, and `docs/yaml-gametest.md`.

  Consequences worth knowing:
  - **`snakeyaml` is gone from both shipped jars** — the harness was its only
    consumer, so the Jar-in-Jar nesting, the `forgeRuntimeLibrary`/`include` wiring
    and `snakeyaml_version` all went with it. Verified: 0 yaml entries in either jar.
  - **`ToolCatalog.HIDDEN_TOOLS` is now empty**, and that is a state rather than a
    leftover: the driver owns no hidden verb any more. Every live hidden verb
    (`mc.test.run` / `mc.test.reset` / `mc.test.input.*`) belongs to StageWright and
    arrives via `registerVerb(..., .asHidden())`. The list stays as the declaration
    site for a driver-owned one, because `baselineNames()` folds it in — an entry
    there is protected from `registerVerb` shadowing, an EXTRA-registered one is not.
  - **`mc.test.*` now has exactly one claimant.** The grandfather clause is deleted
    from `ToolCatalog`, `stagewright/README.md` and `instrument-contract-v0.md`.
  - `ToolCatalogHiddenTest` was deleted with its sole subject. It is not lost
    coverage: `instrument.py`'s check ⑤ pins the same two halves (out of
    `tools/list`; still declared + routable + schema-validated) on `mc.test.run`,
    over the live transport rather than a unit-level list.
  - `64_schema_validation.js`'s "additionalProperties(true) accepts unknown keys"
    sub-test was retargeted from `mc.test.yaml` to `mc.bot.playbook`, which is a
    better subject: its openness is load-bearing product behaviour (the params
    object is injected into the playbook script as the `PLAYBOOK` global), not
    harness laxity.
  - `wd.agentRpcSmoke`'s coverage-drift constants dropped by the script's 5 checks:
    dedicated 147→142 (observed), integrated 259→254 (derived — the script was
    unconditional and pure server-side, so it contributed 5 on both topologies;
    the integrated count check is unreachable while `44_craft` fails first).
  - `DriverApi.seedTestArea` keeps its dy=12 clear ceiling. It was raised to +12 for
    this harness's cells, but the mechanism it fixes (a persistent world means one
    stray block poisons the "cell is air" precondition forever) is a property of the
    world, not of the harness, so the headroom outlives the verb that motivated it.

  Gates: build, source-budget, t0 fabric, t0 neoforge, t2, `instrument --loader
  neoforge` all GREEN. t1 RED with exactly the unchanged pre-existing baseline
  (`wd.vineOverWaterClimb`; `wd.agentRpcSmoke` via `44_craft`).

### Changed
- **Internal: the `Agent*` class names were carrying three unrelated meanings.**
  Follow-up to the WorldDriver rename below. `Agent` meant the API façade in
  `api/`/`model/`, the controlled server-side body in `bot/sim/`+`bot/movement/`,
  and the Rhino layer in `script/` — so a single replacement would have produced
  things like `ServerWorldDriverManager`. Split by meaning instead:

  | Was | Is |
  |---|---|
  | `AgentApi`, `ClientAgentApi(Impl)`, `AgentEvent` | `DriverApi`, `ClientDriverApi(Impl)`, `DriverEvent` |
  | `ServerAgentManager`, `ServerAgentBodies`, `FabricAgentBodies`, `ServerAgentCommand` | `Server*`/`Fabric*` + `Avatar…` (joins the existing `ServerPlayerAvatar` vocabulary) |
  | `AgentFakePlayer`, `AgentInput` | `AvatarFakePlayer`, `AvatarInput` |
  | `AgentScriptManager`, `AgentContextFactory`, `AgentClassFilter`, `AgentEvents`, `AgentTest` | `ScriptManager`, `ScriptContextFactory`, `ScriptClassFilter`, `ScriptEvents`, `ScriptTest` |

  `ScriptTest` is bound into the Rhino scope under its new name, so every
  validation script calls `ScriptTest.run(...)` now. No JSON field or RPC method
  name changed — this is class names only.
- **BREAKING (scripts): the Rhino global `Agent` is now `Driver`.** Every in-game
  script and every `mc.script.eval` payload that said `Agent.invoke(...)`,
  `Agent.bot.*`, `Agent.observe.*` … must say `Driver.*`. The definition lives in
  the canonical `prelude.js` and all 60+ bundled validation scripts follow it.
  `Agent` was the last place the old positioning survived in a public surface: the
  scripts calling it are not agents, they are callers of the driver — same reason
  `AgentApi` became `DriverApi`.

  The bundled scripts also moved from `data/worlddriver/scripts/agent_validation/`
  to `.../scripts/validation/`.

  Note that `Agent` remains the right word for the *consumer* — the L2 LLM layer
  in the design docs, and `gpt-player`'s own `Agent` class, are untouched.
- **BREAKING (naming): the mod is now `WorldDriver` and the test framework is
  `StageWright`.** The old names described the consumers, not this layer. This mod
  is not an agent and not a test tool — it is to Minecraft roughly what chromedriver
  is to Chrome: a control surface driven by an LLM agent, by StageWright, or by a
  hand-written script. Every identifier that carried the old product name moved:

  | Was | Is |
  |---|---|
  | `mod_id`/`archives_name` `agent_driver` | `worlddriver` |
  | `net.magicterra.agent.**` | `net.magicterra.worlddriver.**` |
  | `net.magicterra.testkit.**` | `net.magicterra.stagewright.**` |
  | `AgentDriver*` / `Testkit*` classes | `WorldDriver*` / `StageWright*` |
  | `-Dagent.*` (16 properties, incl. `mcpPort`/`rpcPort`) | `-Dworlddriver.*` |
  | `-Dtestkit.autorun` / `testkit.endpoint` | `-Dstagewright.*` |
  | `<loader>/run/agent-{mcp,rpc}.port` | `worlddriver-{mcp,rpc}.port` |
  | scene prefix `ad.*` | `wd.*` (all 164) |
  | data namespace `data/agent_driver/` | `data/worlddriver/` |
  | gradle plugin id `net.magicterra.mc-testkit` | `net.magicterra.stagewright` |
  | DSL block `testkit { }`, `-Ptestkit.loader` | `stagewright { }`, `-Pstagewright.loader` |
  | tasks `testkitServer/Client/E2E` | `stagewrightServer/Client/E2E` |
  | `mc-testkit/`, `scripts/testkit/`, `docs/testkit/` | `stagewright/`, `scripts/stagewright/`, `docs/stagewright/` |
  | results file `testkit-results.jsonl` | `stagewright-results.jsonl` |
  | subpackage `…worlddriver.bot.testkit` | `…worlddriver.bot.stagewright` |
  | plan docs `2026-07-*-testkit-*.md` | `2026-07-*-stagewright-*.md` |

  **Existing worlds are unaffected** — this mod registers nothing into vanilla
  registries (no blocks, items or entities), so no save data references `agent_driver`.

  The orchestration contract's results filename moved with everything else. It is
  the one name an out-of-tree consumer reads off disk, so it would normally be held
  back — but every consumer is in-house, so a coordinated break was cheaper than
  carrying a second vocabulary forever. Pass `--results <path>` explicitly if you
  have a runner pinned to the old name.

  Downstream: `-Dagent.mcpPort`/`-Dagent.rpcPort` are gone, so any launcher, IDE
  run config or script that pinned the ports must switch to `-Dworlddriver.*`;
  readers of the port files must switch to `worlddriver-rpc.port`, and readers of
  the results file to `stagewright-results.jsonl`.
- **BREAKING (wire): an event's `data` is now a value, not always a string.**
  `DriverEvent.data` was declared `String`, so the 20 structured emitters all
  pre-encoded with `JsonCodec.encode(map)` and the payload shipped as JSON escaped
  inside a JSON string (`"data":"{\"phase\":\"sunset\"}"`). That made the field an
  undiscriminated union — a scalar payload (`block.place` → a block id) and a
  document (`time.phase` → `{phase, dayTime}`) were both just strings, and nothing
  on the wire said which. Emitters now pass the value and the codec encodes it
  once: structured events carry an object, scalar events keep their bare string.
  Affects `mc.observe.eventsSince`, `mc.wait.event`, and the push notifications on
  both transports. **If you `JSON.parse` an event's `data`, remove that call.**

  This was not cosmetic. `gpt-player` detected nightfall with
  `isinstance(e["data"], dict)`, which the escaped string made permanently false —
  its dusk interrupt never fired, the failure its own comment calls "the #1
  historical killer". That consumer now works unchanged; the one that compares
  `data == "minecraft:player"` (a scalar payload) is unaffected.

### Fixed
- **The driver's test arena had no chunk ticket, so its entities existed only while somebody
  stood next to it.** `seedTestArea()` builds at the absolute origin `0,200,0`, which is not
  any scene's arena — `StageWrightHarness` force-loads a window around each *scene* origin
  (hard rule #11) and that window never covers this one. Block writes load the chunk they
  touch on demand, so the terrain was always right; `Level#getEntities` only sees loaded
  entity sections, so the animals and the summoned props were silently not there. What the
  suite reported was `the two seeded props are there, got 0 non-player rows of 0` and
  `exactly the 2 tagged stands, got 0` — i.e. it accused the entity query and
  `execute if entity` of bugs they did not have.

  Both NeoForge client topologies hit it, at opposite ends of the same suite and therefore on
  different checks each run, which is what made it read as flakiness: `05_query` failed before
  the player's teleport onto the pad had promoted the chunk, and `58_query_type` failed after
  `40_scheduler`'s goto had walked them 137,000 blocks away from it. Fabric passed on identical
  code by timing alone.

  `seedTestArea` now takes a non-persistent region ticket around the origin (ENTITY_TICKING out
  to ±2 chunks, covering both the ±20 clear box and the suite's ±16 query radius), drives the
  load to completion before it writes, and **reads its own props back before returning** —
  throwing if the arena it just reported seeding does not hold them. Every earlier way this
  could fail was silent: both `EntityType#create` calls are null-guarded and `addFreshEntity`
  can decline, and none of that reaches the person reading a test report.

  `58_command_result_query_type.js` now asserts its two `summon`s succeeded. A check that
  ignores the return value of its own fixture can only ever describe the symptom.
- **`wd.gearScope` failed at random because its target was on fire.** The scene reported
  "rig broken: a bare-handed swing dealt no damage at all" on roughly one run in three,
  which read as a driver regression and is not one. Arenas only began ticking entities on
  2026-08-05 and the harness does not pin world time, so the probe's zombie — NoAI, under
  open sky — ignites on a per-tick dice roll (`Zombie#aiStep` → `isSunBurnTick`). That is
  the whole of the intermittency. One fire tick then refuses the entire measurement: inside
  i-frames vanilla only lets a hit through when it *exceeds* `lastHurt`, and a bare fist's
  1.0 does not exceed a fire tick's 1.0, so `probeSwing` returned a flat 0.

  The target now carries fire resistance, which keeps the burn out of the damage math
  without touching melee (`FIRE_RESISTANCE` is read by `isInvulnerableTo`, never by
  `actuallyHurt`), and its i-frames are zeroed at the instant of the swing so residue from
  *any* source cannot refuse it. The avatar had this protection all along via
  `grantWaterEffects`; only the target went without.

  A/B'd against the failing state injected deterministically, so the "before" leg fails
  every run instead of one in three. Before: `bareHand=0.0`, `ironSword=4.92` — the sword
  showing the same rule from the other side, `6.0` less `lastHurt`. After, same injection:
  `bareHand=0.94000053`, `ironSword=5.9040003`, the exact values this scene has always
  recorded. With the injection removed the new diagnostic never fires, so the burn is not
  merely survived — it no longer happens.

- **The walker could not break a single block on a client. Every dig site now drives the
  destroy directly.** The seven break sites under `bot/movement/` did `breakHold(true)` and
  waited for vanilla's `continueAttack → continueDestroyBlock` pipeline, which is gated on
  `mouseHandler.isMouseGrabbed()` — never true for a driven client. The processes were fixed
  for this a day earlier; the walker was not, and its one direct drive sat behind
  `StickyDig`'s ray-miss latch, which only arms after the crosshair has *wandered off* the
  target. A dig aimed correctly therefore never reached it: the bot stood against the block
  holding an attack key that did nothing, forever.

  Measured, same rig both ways — a goal cell sealed inside a solid dirt shell so the only
  route in is through one block. Before: `goto` awaited its full 60 s, `completed=false`,
  both door cells still dirt, bot parked at x=15.26 against the wall. After: arrived in
  4.6 s, the two door cells air, bot at x=18.34 inside. Neighbouring shell cells untouched —
  it digs the doorway it needs, not a hole.

  `Walker.avatarDig` is now the only way these sites break: it holds the key *and* drives the
  destroy, so the pair cannot be half-written at a new site. `StickyDig.direct` no longer
  decides *whether* to drive — only whether to release the key, which is what it was really
  for. `Avatar#continueDestroy` gained a same-cell-same-tick guard, because two walker phases
  can now aim at one cell in a tick and each call advances vanilla's break by a tick's worth.

  No server-side behaviour changed at all: `ServerPlayerAvatar` inherits `continueDestroy` as
  a no-op, which is exactly why all 180 `wd.server*` scenes passed throughout and could not
  have caught this. Both t0 gates re-run GREEN, byte-identical outcomes.

- **26 `.pyc` files were tracked, so `git status` was never clean.** `.gitignore`
  had covered `scripts/**/__pycache__/` for a long time, but it was added *after*
  the bytecode had been committed and an ignore rule does nothing for a tracked
  file. The cache spanned three interpreter generations (`cpython-312` from the
  Linux host, `313`/`314` from a Windows checkout), so merely running the pmcs
  scripts under a different Python rewrote them and they showed up as modified —
  build output presenting itself as work (hard rule #5). Untracked with
  `git rm --cached` (files left on disk) and the two `scripts/`-scoped globs
  replaced by repo-wide `__pycache__/` + `*.py[cod]`, which also covers
  `path-replay/` without relying on its nested `.gitignore`.
- **`rpc.py` waited out its timeout on errors the server had already explained.**
  `RpcServer` answers a request too malformed to carry an id with
  `{"id": null, "error": "parse: …", "code": -32700}`, and its class doc states the
  demux rule: the `id` **key** is on every response and absent from every
  notification. `call()` matched with `msg.get("id") != rid`, so an id-less error
  failed that test, hit the `continue`, and blocked on `recv()` until `--timeout`
  — the tool reported a timeout while the server had sent the exact reason. It now
  skips frames with no `id` key (notifications), accepts `id: null` as its own
  (exactly one request is ever in flight on this client), and appends the JSON-RPC
  `code` to the error line. `event_tail.py` goes through the same `call()`.
- **A comment claimed a use-key ownership that does not exist.** `BotApiImpl`'s
  `processOwnsUseKey` said "the only process contender is BuildProcess (PLACING) — it
  owns the key then". Six process kinds answer `"builder"` (Build/Bridge/Tower/
  Backfill/BboxFill/Farm), and none of them presses `keyUse` — they place through
  `gameMode.useItemOn` directly, as the same comment says two lines earlier. The flag
  suppresses the ambient reflexes so their `keyUse` cannot fire a second use-action on
  the tick a builder places; renamed `builderSuppressesAmbients` to say that. Behavior
  unchanged.
- **`:common:test` was writing runtime logs into the source tree.** The test JVM
  runs with the module directory as its working directory (the source-scanning
  tests need that), so log4j2 — configured by Minecraft's own config off the test
  classpath — created `common/logs/latest.log` plus a rolled `.log.gz` per run.
  `.gitignore`'s `*.log` covered the former and not the archives, so they piled up
  untracked and un-ignored, one per test run, inside `common/` (hard rule #5). A
  console-only `common/src/test/resources/log4j2-test.xml` now takes precedence
  over the game's config, so nothing is written at all; `logs/` is also ignored in
  case another working directory produces one.
- **A malformed `mc.events.subscribe` filter failed OPEN.** `types` was read with
  `instanceof List`, so `{"types":"chat.message"}` simply did not match, the set
  stayed empty, empty meant "no filter" — and the caller was subscribed to **every**
  event while its ack said `types:[]`, which reads like the opposite. A non-string
  entry was dropped just as quietly. Both now return `-32602`. The ack also carries
  `allTypes`, because an empty `types` cannot say on its own whether it means "all"
  or "none", and that ambiguity is what the silent path led into. Event type names
  stay unvalidated on purpose — scripts mint their own via `mc.events{op:'emit'}`
  and watcher `emitAs`, so the set is open-world and a typo still yields silence.
- **The WebSocket transport silently dropped requests over 64 KiB.** `McpServer`
  capped a POST body at 8 MiB and documented it; `RpcServer` never set a frame size
  and inherited Netty's 64 KiB default, so the same `DriverApi` call succeeded on one
  transport and killed the connection on the other at 128× less payload — with no
  JSON error, because a frame that never assembles carries no id to answer. Both
  limits now come from `TransportLimits.MAX_REQUEST_BYTES` (8 MiB, override with
  `-Dworlddriver.maxRequestBytes=N`, replacing the MCP-only `agent.mcp.maxBodyBytes`).
  `RpcClient` gets the same ceiling on inbound frames, where it matters just as
  much: responses are the big direction (`mc.client.screenshot` returns base64
  image bytes) and the 64 KiB default would have turned an oversized reply into a
  call timeout.
- **One stalled MCP SSE client no longer freezes the whole event system.**
  `McpServer.onEvent` wrote each SSE socket inline, on DriverApi's *single*
  event-dispatch thread — the one every listener shares. A client whose TCP receive
  window had filled parked that thread inside `os.write`, taking down every other
  SSE subscriber, **the WebSocket push channel**, and letting the dispatch queue
  (unbounded) grow for as long as the stall lasted. The WebSocket transport never
  had this problem because Netty's `writeAndFlush` is async; the MCP side had no
  equivalent. Each subscriber now has a bounded outbox drained by the HTTP worker
  thread that was already parked on that connection — so the writes moved off the
  shared thread without adding one. Overflow closes the stream instead of silently
  discarding frames: a consumer that misses events cannot tell that it did, whereas
  EOF is loud and recoverable via `mc.observe.eventsSince{cursor}` replay.
  (Unchanged: a client that wedges and never closes its TCP connection still holds
  its own HTTP worker thread — now only its own.)
- **`mutedEvents` is applied once, in `DriverApi`, instead of once per transport.**
  `RpcServer.onEvent` and `McpServer.onEvent` each carried their own copy of the
  same `BotConfig.mutedEvents.contains(...)` line — a policy decision living in two
  transport handlers, which AGENTS.md hard rule #1 exists to prevent, and the shape
  where a third transport is muted only if its author remembers to be. The check
  now runs on the dispatch thread before any listener is called, so the timing is
  unchanged, and muting still suppresses the push only: the event is appended to
  the replay ring first, so `mc.observe.eventsSince` returns it exactly as before.
- **`logging/setLevel` no longer stores a value nothing reads.** The MCP server
  deliberately does not gate driver events on the client's severity minimum (a
  client defaulting to `warning` would silently drop every info/notice event, which
  `onEvent` documents) — but it still recorded the level into a field that was
  written and never read, and the class doc claimed the filter was "honored",
  contradicting the two comments that said it was not. The request is still
  accepted and acknowledged per spec; the dead field and the `EventNotifications
  .rank()` helper that existed only to feed it are gone, and the doc now states the
  actual behavior.

- **WebSocket RPC: a pushed event could be returned as a call's response.** Every
  frame the socket receives shares one queue, and `RpcClient` took whichever
  arrived next as its answer. On a connection that had run `mc.events.subscribe`,
  one pushed `notifications/message` was therefore returned in place of the
  response — no `result` key, so the call quietly yielded `null` — and the real
  response stayed queued, shifting **every subsequent call on that connection by
  one frame**. The client now demultiplexes the way the transport's own class doc
  specifies (a frame with a `method` is a notification; a frame with an `id` is a
  response), matches responses by id, and drops stale ones. In-repo the only
  caller (`RpcBridge`) never subscribes, so nothing shipped was mis-answering;
  `gpt-player/driver.py` and the worlddriver-rpc skill's `rpc.py` already
  correlated by id.
- **WebSocket RPC error frames now always carry `id`, plus a JSON-RPC `code`.**
  The two malformed-input paths (`request must be JSON object`, `parse: …`)
  omitted the `id` key entirely, contradicting the demultiplexing rule the same
  class documents and leaving an id-correlating client to wait out its timeout
  instead of seeing the error; a frame whose id *had* been read lost it too. `id`
  is now always present (explicitly `null` when unknowable) and `code` carries the
  JSON-RPC 2.0 reserved code `McpServer` already emits for the same failure, so
  the two transports finally agree on classification. `error` stays a bare string
  — `gpt-player` and `rpc.py` both read it as one, and reshaping it into MCP's
  `{code, message}` object would break them for nothing.

### Added
- **The `keyUse` acquirer set is pinned.** `keyUse` is the one shared input
  `releaseKeys()` deliberately does not clear — the idle release runs after the
  shield/heal/eat reflexes set it, so a blanket clear would undo them every tick.
  What replaces it is a hand-rolled arbitration (shield > heal > eat, losers release)
  that nothing enforces membership in. A fifth acquirer skipping it either gets
  clobbered mid-action or leaks, leaving the bot walking with right-click held —
  placing blocks, eating its food, drawing a bow it never fires;
  `CombatChain#releaseUseKey` exists because that leak already happened once on the
  combat preempt path. `UseKeyOwnershipTest` fails on a new acquirer file and on
  `keyUse` being added to the blanket release, each verified by injection.
- **Every `mc.bot.setting` key is now checked to have a reader.** `SettingsRegistry`
  already kept the key set, the snapshot, the schema and the docs agreeing about
  which of the 218 knobs exist — nothing asked whether a knob does anything. Since a
  new `BotConfig` field surfaces on the settings API automatically, a flag whose read
  site never landed (or whose behavior was later refactored away, leaving the knob)
  is accepted, echoed back as set, and changes nothing: success signalled all the way
  to the caller. `SettingsConsumerTest` takes the key set from
  `reflectivePrimitiveFields()` — the same enumerator the live surface is built from,
  not a re-parse — and fails on any key nothing reads. All 218 are consumed today;
  verified discriminating by adding an unread knob and by deleting the single reader
  of a real one (`walkerChainMount`).
- **The `WalkerTickCtx` handoff contract is now enforced by a test.** Nine phases
  hand 31 fields to each other through a per-tick struct, in an order only
  `Walker#tickInner` knows; the rule "a phase writes its own product group and
  reads only what earlier phases produced" lived in a javadoc and a hand-derived
  census in `docs/walker-tick-architecture.md`. `WalkerTickDataflowTest` re-derives
  that census from source on every test run — reading the phase order out of
  `tickInner` rather than hardcoding it — and fails on a read-before-write, on a
  ctx field with no producer or no later consumer, and on the phase files and the
  driver's call list disagreeing. Read-before-write is the one worth a gate: a
  phase reading a later phase's product gets the zero value on every tick, with no
  exception and no log. The pipeline is clean today; this keeps it that way.
- **Pathfinder cost attribution** (`-Dworlddriver.pathfinderTaxLog=true`). Up to ten cost
  modifiers are summed into every A* edge, several pricing overlapping situations,
  and the search reported one opaque `finalCost` — so when a route surprised you,
  nothing said which tax produced it. Each search now logs a per-tax breakdown in
  two columns: what the taxes charged **during the search** (what shaped the
  decision) and what the **winning path** actually paid; the gap between them is
  the avoidance a tax achieved. Registration goes through one `tax(name, modifier)`
  helper so a new tax cannot be added without a name. The hot loop's arithmetic,
  order and values are unchanged, and the counters sit behind a `static final` flag
  the JIT folds away when off.

  The first run over the 165-scene suite (925 searches) found something the obvious
  version of this instrument would have missed: `submerged`, `vineOverWater` and
  `climbOut` charge thousands of cost units during the search and **zero** on the
  winning path — which is a correctly working avoidance tax, not a dead one.
  Measuring only the final path, as the first cut did, called four of them dead.
  `descendTax` and `padCellTax` by contrast charge nothing at all in any search:
  their inner conditions never hold anywhere in the suite, so two tunable constants
  (`pathfinderDescendCost=40`, `pathfinderLilyPadCellCost=20`), each added for a
  cited live incident, have no test coverage at all.
- **`common/src/test` — a JUnit 5 source set for game-free code**, wired into
  `build`. 27 tests over the transports, all of them covering behavior that could
  previously only be reached through a full dogfood boot — which is why every bug
  in this section survived every existing gate.
- **`scripts/check_scene_arena.py` — scene footprints must fit their forced-chunk
  window.** `StageWrightHarness` force-loads `(2r+1)²` chunks around each scene origin
  (`Scene.withChunkRadius`, default 1 → usable `dx,dz ∈ [-16r, 16r+15]`); building
  terrain outside it still succeeds, so the scene passes most of the time and
  fails when it doesn't, reading as a bot bug. The relation was maintained purely
  by hand in javadoc. Source-only gate: interval-evaluates each body's origin
  offsets across all four terrain idioms in the corpus and reports UNRESOLVED —
  never OK — when it cannot attribute a body's block writes. 164/164 scenes fit.
- **`mc.world.block` — read-only single-cell inspection** `{pos, type, state?,
  light:{block,sky}, blockEntity?}`: blockstate property map (`lit`/`facing`/`half`
  as `/setblock`-style strings), light levels (previously unreadable through any
  surface), and opt-in block-entity SNBT. The verify half of a build→verify loop
  (docs/feedback/2026-06-08 asked for exactly this; every verification used to be
  an `execute if block … run setblock <scratch>` hack). Also exposed to scripts as
  `Agent.world.block/snapshot/restore`.
- **`mc.query` entities rows gain `uuid`, `id`, and `effects`**
  (`[{id, amplifier, durationTicks}]`, living entities only) — the MobEffect-read
  gap external consumers ranked as their single biggest blocker for testing
  effect-based mechanics (docs/feedback/2026-06-04 #4); `filter.is_living`
  drops item/XP-orb rows that polluted health-delta assertions (#6).
- **`mc.query` blocks rows gain `state`** — the blockstate property map, omitted
  for property-less states; the client-MCP fallback rows carry it too
  (docs/feedback/2026-06-08 #2).
- **Server-side `mc.observe.player` now returns `effects`** — the client snapshot
  grew it first, but headless dedicated servers (the main external-consumer
  scenario) read the player through the server path, which still lacked it.
- **Guard scripts 58–61** for all of the above plus the runCommand-outcome and
  entities-`filter.type` fixes (both had shipped without tests), including a
  per-variant stairs place→query sweep closing the 2026-06-07 "query is blind to
  stairs" report (not reproducible on today's scan code; the sweep keeps it closed).
- **Guard script 62 — `in_radius` membership sweep** closing the 2026-06-04 §C
  "radius query silently missed an entity 1 block away" report the same way: a
  pinned armor-stand ring (same cell, the reported dz=+1 shape, axis/diagonal/
  vertical extremes, out-of-range ring, r=0) pins the block-symmetric AABB-slab
  semantics. Green on today's scan code — no static geometric hole; the leading
  suspect for the original miss is a death race (server had removed the mob
  while its death animation still showed it standing). The sweep keeps the
  geometry closed. Each block is hermetic (review follow-up): defensive
  pre-kill, own summons, kill in a `finally` — a failed assertion can't leak
  pinned stands into later suites, and the r=0 case no longer depends on the
  r=3 block's leftovers.
- `mc.bot.useItem` third mode `entityId` — right-click an entity (vanilla
  `interactAt`→`interact` parity): mount boats/saddled horses (empty hand),
  open villager trade UI, shear/milk/feed/tame/leash. New optional `sneak`
  param for sneak-gated interactions. Returns `riding`/`screen` so one call
  confirms whether a mount/UI landed. No new tools (Hard Rule #6).
- **`mc.bot.lookAt` and `mc.observe.player` lag documentation** — lookAt updates
  reach the SERVER entity one tick after the call returns; observe.player().look
  reads pre-lookAt angles until the next tick. Tool descriptions now document this
  timing and recommend `waitTicks(1)` before asserting (docs/feedback/2026-07-10 §3).

### Fixed
- **descentYawArena "flakiness" convicted and cured — it was a rig defect, not a
  thinning walker margin.** Sixty days of archived gametest logs showed the yaw
  totals were byte-identical across days per suite build (1935°×3, 1893°×3,
  2386°×2, 729°×2): the run is deterministic, and the "flaky" spread came from
  which un-restored BotConfig flags earlier batches leaked into it. Worse, since
  07-02 the bot never ARRIVED — it sprinted off the 19×19 built strip into the
  void (terminal y=-60), the loop then measured 400+ ticks of mid-air/void-floor
  anti-stuck spin instead of descent thrash, and the `reached` check (no lower y
  bound) still passed runs that were falling when x/z crossed the corner. Fix
  (rig hardening): NE run-out plateau (dx/dz clamped to span+8) so overshoot
  lands and walks back; `BotConfig.applyGameTestBaseline()` re-applied at arena
  start to kill inter-batch flag leaks; `reached` gains `y >= goalSurf-1`.
  Post-fix: deterministic ARRIVED@~299t, 993° (byte-identical ×3 solo runs), and
  `AGENT_GT_ONLY` solo runs now terminate in ~17s (the old "solo hangs" was the
  void-floor bot grinding 100k-node searches). Ceilings re-documented against
  the deterministic baseline (yaw 1200 vs 993; backSteps 90 vs 67). Same disease
  family (void fall + vacuous reached) still lives in descentOvershootResync /
  riverSheerBank / vineOverWater — the hardening template applies, tracked as a
  follow-up. Review follow-up: the baseline re-pin itself was a leak — it flips
  ~37 flags but the finally restored only this arena's five keys, so a `/test`
  run on an integrated server left the live bot with the legacy-OFF baseline
  (violating `applyGameTestBaseline`'s "live clients never call this"
  invariant). The arena now snapshots EVERY mutable config key
  (`BotConfig.snapshotAll()`) and restores them all in the finally
  (`restoreAll`) — the reusable rig template for the other arenas.
- **seeded query-prop cow/sheep are now NoAI** — the wandering cow stepped one
  block between 06_rpc_parity's two snapshots (in-JVM vs TCP, 15 ms apart) and
  failed the row-equality assert. They are props for entity-query assertions,
  not livestock.
- **`mc.client.overlays` TutorialSteps reflection is now robust** — the lookup used
  a bare class name (`Class.forName("TutorialSteps")`) and threw in every runtime,
  not just mojmap dev. Replaced with a direct import + field write so both vanilla
  and intermediary runtimes succeed (docs/feedback/2026-07-10 §2).
- **`DriverApi.route()` validates params against the MCP schema — single source of
  truth** — wrong-argument errors now name missing required fields and unexpected
  keys instead of silently consuming them or returning a generic message. `{command:…}`
  (missing required `cmd`) now fails with `invalid params for mc.action.runCommand:
  missing required 'cmd' (string); unexpected key 'command'` on every transport
  (RPC, MCP, in-JVM scripts). `SchemaValidator` unmarshals JSON against the same
  `ToolSchema` the catalog advertises, so advertisement and enforcement cannot
  drift (docs/feedback/2026-07-10 §4).
- **chat readback is now usable: `mc.client.chat.history` / `chat.send awaitReplyMs`
  read a packet-level buffer (`ClientChatLog`) instead of reflecting on the GUI's
  `ChatComponent.allMessages`** (docs/feedback/2026-06-08 "chat is not a usable
  readback channel"). The GUI list is newest-first, hard-capped at 100 and
  re-indexes on every arrival, so the old code returned the *oldest* buffered
  line as the "reply" (on a busy server: some other mod's broadcast), went
  permanently blind once 100 lines had ever arrived (size stops changing, so
  "new message" checks never fire), and swallowed every reflection failure into
  an empty result (on Fabric-intermediary runtimes the field fallback even
  grabbed `recentChat` — the *sent*-message history). The new buffer is fed by
  the platform chat-received hooks (Fabric `ClientReceiveMessageEvents.CHAT/GAME`,
  NeoForge `ClientChatReceivedEvent`; action-bar overlay excluded), keeps a
  monotonic `seq` over the last 512 lines, tags each row `kind:"system"|"player"`
  (command feedback = system) for correlation, and `awaitReplyMs` now returns
  only lines that arrived *after* the send, with a ~150ms settle window so
  multi-line feedback batches into `reply`/`replyExtra`. The `client.message`
  event stream drains the same buffer (rows gain `kind`) instead of doing its
  own GUI reflection. Guarded by the `clientChatLogSemantics` gametest.
- **Chat readback review round (found by an 8-angle code review of the above,
  all fixed before merge):**
  - *Self-echo returned as the "reply"* — since 1.19 signed chat the server
    echoes your own plain-chat line back as a packet, so `awaitReplyMs`
    returned the message the bot itself just sent and dropped the real answer.
    Entries now carry a `self` flag (sender == local player), reply correlation
    skips self lines entirely, history keeps them (rows gain `self`).
  - *Loader kind divergence on disguised chat* — console//command-block `/say`
    and proxy-relayed unsigned chat was `kind:"player"` on Fabric but
    `"system"` on NeoForge. Classification now lives in ONE common funnel
    (`ClientChat.recordReceived`): "player" iff the line carries a real sender
    profile, so both loaders file disguised chat as "system".
  - *Chat-filter-mod blind spot* — lines other client mods cancel (typical
    cancel-and-redisplay chat managers) never reached the log on either loader.
    NeoForge now subscribes with `receiveCanceled=true`, Fabric additionally
    registers `CHAT_CANCELED`/`GAME_CANCELED`: the readback channel records
    what the server delivered, not what survived other mods' filters.
  - *Capture boundary documented* — lines added client-locally without a packet
    (Fabric client-command feedback, vanilla chat-validation errors, mods
    calling `ChatComponent.addMessage` directly) render on screen but never
    cross the packet layer; the old GUI scrape saw them, the packet log cannot.
    Tool descriptions + methods.md now state this instead of implying "the
    scrollback".
  - *Tick-safety regression* — the rewrite dropped the old drain's
    catch-everything guard while `BotApiImpl.clientTick` still promised
    "never breaks the tick"; an encode/emit throw would have escaped into the
    vanilla tick loop with movement keys latched. The drain is re-guarded, the
    cursor advances before the emit (drop one line, never wedge), and
    `record()` normalizes a null kind to "system" so the event `Map.of` can't
    NPE.
  - *Test isolation* — `clientChatLogSemantics` cleared and flooded the
    process-global live log (a dev-client `/test run` shares the JVM with real
    chat history, the `client.message` drain and in-flight `awaitReplyMs`).
    The buffer core is now an instance class (`ClientChatLog.Buffer`) with a
    static facade for the live session; the gametest runs on a private
    instance and `clearForTest()` is gone.
  - *Hot-path cost* — `since()` was an O(cap) scan + list alloc under the
    global lock every client tick (and every 50ms awaitReply poll) even when
    nothing new arrived; all pollers now probe `nextSeq()` first (O(1)) and
    `chatHistory` builds rows newest-first straight off the tail instead of
    materializing up to 512 rows to trim to `limit`.
- **Chat readback / rig-template review round 2 (a second 8-angle review of the
  above two features, all fixed):**
  - *NIL_UUID normalization pulled INTO the funnel* — the "one classifier"
    still left disguised chat's NIL_UUID→system translation at the NeoForge
    call site; a loader handing a NIL sender down the player path
    (proxy-relayed/unsigned chat edges) would have re-opened the kind drift
    and let `awaitReplyMs` return it as a real reply. `recordReceived` now
    normalizes null AND NIL_UUID to "system" itself, and the action-bar
    overlay exclusion moved in with it — capture policy has one home, the
    loader taps only translate event shapes.
  - *`since()` O(cap)→O(k)* — the seq is contiguous and the deque ordered, so
    `since()` now walks back from the newest entry and stops at the first
    `seq < sinceSeq` instead of scanning all 512 retained lines under the
    global lock (chat floods queued `record()` behind the per-tick drain);
    `chatHistory` gets a `tail(cap, sinceSeq)` slice that never materializes
    the entries the cap discards (covered by new `clientChatLogSemantics`
    assertions).
  - *`client.message` rows = history rows* — the event drain hand-rolled a
    `{text,kind}` subset, so event consumers couldn't reconcile against
    `chat.history` seqs or skip the bot's own echo. The row encode is now
    `ClientChatLog.Entry.row()` (`{seq,kind,text,self}`), shared by history,
    awaitReply and the event stream; javadoc states the chat seq and the
    `eventsSince` cursor are separate sequences.
  - *Rig template unlosable* — the snapshot→`applyGameTestBaseline`→restore
    triple lived as loose arena code, one missed `finally` away from
    re-leaking the legacy-OFF baseline as the other void-fall arenas copy it.
    Now `BotConfig.pinnedBaseline()` returns the restore as an AutoCloseable
    (`try (var pin = …)`); descentYawArena uses it and reproduces the
    deterministic baseline byte-identically (ARRIVED, 993°, backSteps=67).
    `snapshotAll`/`restoreAll`/`save`/`load` also share ONE
    `persistableFields()` enumeration so the persisted set and the snapshot
    set can't drift.
  - *Misc* — `chatHistory` takes `sinceSeq` as long end-to-end (the returned
    `nextSeq` is long; the int boundary silently truncated the contract);
    Fabric registers one method per event family for normal+CANCELED so the
    pairs can't drift; new tests use `AgentGameTestSupport.gtSkip()` instead
    of the copied `AGENT_GT_ONLY` guard line (double `getenv`, typo→false
    green, and an FQN the conventions ban).
- **Walker stall-clock water starvation (REGRESSION §94)** — `STUCK_PROGRESS_EPS`
  0.02→0.05 (the C40-J1 dry wall-creep fix, 07-03) made every water node
  approach's slow rounding manoeuvre (~0.02-0.05 blk/tick lateral) read as "no
  progress"; the tripped reCentre/wiggle recovery pinned the bot on obstacle
  corners (waterFarAimBankCornerArena deterministic dGoal=6.33 pin, convicted by
  git-bisect → 31bdb5c, single-variable verified). Fix: medium split — dry keeps
  0.05, water uses new `STUCK_PROGRESS_EPS_WATER=0.02`.
- **`mc.query` select rejects unknown keys** (`isError` naming the bad key and the
  allowed set) instead of silently dropping them — callers were misled into
  "field not supported" detours (docs/feedback/2026-06-04 #3).
- **`mc.action.runCommand` setblock fast-path no longer throws "invalid block id"
  on `[state]`/`{nbt}` syntax or trailing keep|destroy|replace modes** — those
  now fall through to Brigadier (caught by the new guard scripts: the fast-path
  ate `setblock … oak_stairs[facing=south,half=top]`).
- **Pinned RPC/MCP ports fall back to an ephemeral port when already bound**
  (WARN + `run/agent-{rpc,mcp}.port` records the real port) instead of dying with
  a mid-log BindException — two instances now coexist by default
  (docs/feedback/2026-06-04 port-conflict UX).
- **Published POM/metadata no longer leak the Jar-in-Jar'd Rhino as a consumable
  dependency** (naive consumers got a second Rhino on the classpath —
  docs/feedback/2026-06-04 #2; module metadata is disabled so the cleaned POM is
  the single source of truth).
- **`agentRpcSmoke` runs in its own GameTest batch** — sharing a batch with
  wall-clock-hungry walker/pathfinder arenas starved its 8s `onServerThread`
  deadline into 46 false FAILs.
- **`mc.query` entities gains `filter.type`** (exact entity id; bare path → `minecraft:`),
  mirroring the blocks branch — the docs promised it for both, only blocks had it.
- **`mc.action.runCommand` now returns the command's own outcome** — `success`/`value`
  from the Brigadier result callback (`execute if entity` → match count) and
  `feedback[]` with the collected chat output (`data get` → NBT text) instead of
  suppressing it. `ok:true, success:false` = dispatched but the command failed
  (e.g. selector matched nothing). Unblocks headless assertion of entity NBT /
  MobEffects that `mc.query` can't project.
- **`mc.bot.goto` gains `hugShore` (shoreline-affinity bias) — 沿着河岸走 = goto far
  point + hugShore + forbidWater.**
- **Bias-aware string-pull**: the path smoother no longer straightens a bow the
  per-intent bias paid for — protects ALL bias citizens (hugShore, avoid, preferY,
  leash), found via a ShorelineHug bank-hug collapsed straight across the taxed dry
  interior (the dangerCost-smoother lesson, replayed for the intent layer's bias
  channel and fixed the same way).
- **`mc.bot.goto` leash/leashHard accept `entity:'name-or-type'` — a DYNAMIC anchor
  re-solved as the entity moves (带路 scenarios); `mc.bot.follow` accepts the goto
  bias/constraint args (compose "follow A but forbidWater/avoid zones").** The hard
  leash gains rejoin semantics: if the bot falls outside the tether (e.g. the anchor
  teleports away), it is no longer fully pruned — only edges that strictly approach
  the anchor are allowed, so it routes straight back into the radius and resumes
  normal leash behavior.
- **`mc.bot.goto` gains `forbidWater` (never route through water), `forbidDig` (never
  plan a block-breaking edge — per-goto `allowBreak`-off), and `requireTool` (fail fast
  unless the named item is in inventory) via the intent layer's Constraint channel.**
- **`mc.bot.goto` gains hard navigation controls via the intent layer: `forbidParkour`
  (drop parkour moves), `yFloor`/`yCeil` (hard-limit route Y), and `leashHard`
  (firm radius tether — the hard twin of the soft `leash`). Enforced by a per-intent
  `CapabilityProfile` (move-type gate) and `Constraint` edge-prune in the pathfinder.**
- **`mc.bot.goto` now accepts per-navigation cost bias — `avoid` (route around
  zones), `preferY` (stay in a Y band), `leash` (soft-stay near an anchor) — via
  the LLM navigation intent layer's `CostModifier` bias channel. Intent-scoped
  (cleared when the goto ends), unlike the global `avoidPoints` setting.**
- **Path archive / replay / analysis toolchain — deterministic wedge reproduction.**
  Three pieces: (1) `mc.bot.setting{pathArchive:true}` enables per-session recording
  (default **OFF** — heavyweight); on `goto` completion a self-contained JSON archive
  lands in `config/worlddriver/replays/replay-<n>-<epochMs>.json` containing the
  world seed, dimension, each progressive segment's planned path + edges + per-node
  physics facts (pose fit, collision, hazard, fall height, jump feasibility), a sparse
  block envelope (±2 XZ, −1..+2 Y around every node), and the per-tick executed
  trajectory. (2) `mc.debug.replay {file?:<latest>, restoreBlocks?:true,
  fromStep?:0}` — restores the envelope into the world (block type faithful;
  blockstate properties reset to `defaultBlockState`), teleports the bot to
  `header.start`, and re-executes the stored plan through the real Walker with no
  re-planning, recording actual trajectory + per-step deviation in a
  `replay-run-*.json`. (3) `path-replay/analyze.py <archive.json> [--all] [--step N]
  [--replay <run.json>] [--deviation-threshold FLOAT]` — a standalone Python script
  that prints an aligned per-step table (columns: step, pos, move, pose, fit,
  underfoot, fall, jump, break, place, dev, flags) and anomaly tokens: `SUFFOCATE`,
  `CEILING:CROUCH/CRAWL`, `COLLIDE`, `HAZARD(<name>)`, `FALL!`, `JUMP✗`, `DRIFT`.
  See `path-replay/README.md` for usage, column reference, and example output.
- **Claude Code channel bridge (`scripts/agent_channel_bridge.py`) — makes the mod
  consumable as a native Claude Code "channel".** Claude Code's push protocol is
  `notifications/claude/channel` over **stdio** (it spawns the channel server as a
  subprocess), not the generic `notifications/message` the mod emits — so this is a
  dependency-free (Python stdlib) stdio shim. It (1) proxies the mod's tools
  (`tools/list`/`tools/call` over the mod's MCP HTTP) so Claude can drive the bot,
  and (2) forwards live events (threat/hurt/death/chat/…) into the session as
  `notifications/claude/channel`, marking `chat.message` `untrusted` (player-typed
  text is an injection surface). Built for the dev loop: it answers `initialize`
  immediately even with the mod offline, **auto-waits** for the mod's MCP port, and
  **refreshes the tool list** (`notifications/tools/list_changed`) on first connect
  and on every reconnect — a mod restart mid-session just blips offline→online
  (verified live: kill client → `tools/call` returns a graceful error → relaunch →
  auto-reconnect + tool-list refresh + tools work again). Register via
  `scripts/worlddriver-channel.mcp.json.example` and launch with
  `claude --dangerously-load-development-channels server:worlddriver` (custom
  channels need the dev flag during the research preview).
- **Driver→agent event push channel — the driver streams events to the agent in
  real time instead of the agent only polling.** Every event still funnels through
  the single `DriverApi.emit(...)` (ring buffer for `mc.observe.eventsSince` replay,
  unchanged) which now also fans out to live push subscribers off a dedicated
  dispatch thread (the game tick never blocks on a socket). Every event is a
  standard server→client **`notifications/message`** (the MCP logging notification —
  `params.level` mapped from the event type, full event in `params.data`), the shape
  an MCP-aware agent loop already consumes, byte-identical on **both transports**:
  **MCP** — the spec's server→client SSE: after `initialize` (server advertises the
  `logging` capability), the client opens `GET /mcp` with `Accept: text/event-stream`
  and receives the notifications as `data:` lines; `logging/setLevel` sets a minimum
  severity. **WebSocket `/rpc`** — opt in with a `mc.events.subscribe` control frame
  (`{types:[...]}` filter; `unsubscribe` to stop); connections that never subscribe
  (incl. the parity harness) get nothing extra. The `/mcp` POST request/response path
  is unchanged. Event sources: the existing block/death/chat
  hooks now push; new `command.result` (every Brigadier `mc.action.runCommand`),
  and client-tick detectors for `threat.appeared` (敌袭), `player.hurt` (受伤),
  `player.death` (死亡). New `mc.events` route — `op:emit` injects a custom event;
  `op:watch`/`unwatch`/`list` register rising-edge condition watchers (poll a route,
  emit `emitAs` the first tick a predicate flips false→true, e.g. health `below` 6).
  Prelude `Agent.events.{emit,watch,unwatch,list}`; catalog tool `mc.events`.
  Validation `49_events.js` (4 sub-tests: emit→replay, emit-rejects-bad, watcher
  rising-edge fires, watch/list/unwatch lifecycle) — suite now **91 GameTest cases,
  all green**. Live push verified in runClient on both transports (MCP `GET /mcp` SSE
  and WebSocket), as identical `notifications/message`, for all six event categories.
  (`AgentGameTest`
  `timeoutTicks` widened to 100000 — the GameTest server time-compresses ticks, so
  the watcher's ~0.5 s real-time wait needs a larger wall-clock budget.)
- **YAML → GameTest transpiler — declarative test cases over the agent routes
  (proposal §4.1 C, Phase 2).** A YAML test (`docs/yaml-gametest.md`) is a list of
  `{name, region, setup, asserts}` cases; `YamlTestInterpreter` runs each as
  `snapshot → setup → asserts → restore` (the `finally` restore is why this layer
  needed the `mc.world.snapshot`/`restore` primitive below — cases never pollute
  each other). Every setup verb (`place`/`place_many`/`fill`/`run_command`/
  `wait_ticks`) and assert (`block_present`/`block_absent`/`entity_present`, with
  `namespace:*` wildcards) dispatches through `DriverApi.route(...)` — the same
  single entry the JS/WS/MCP transports use, so a YAML test exercises the real
  production path with no parallel implementation to drift. Parsing is snakeyaml
  under `SafeConstructor` (the one dependency we shadow-**relocate**, since it's a
  high-collision library, unlike the unrelocated Rhino/netty); files are
  enumerated from `data/worlddriver/gametests/index.txt`. New `mc.test.yaml`
  route (`{inline}` / `{file}` / `{all:true}`) returns
  `{results:[{name,pass,failures}], passed, failed}`. Validation script
  `34_yaml_gametest.js` (5 sub-tests: inline run, region-restore, classpath-file
  load, index manifest, and failure-is-reported) plus a real
  `smoke_place_observe.yaml` — suite is now **65 GameTest cases, all green**.
  **Not** wired as per-case `@GameTestGenerator` tests: a batch's GameTests run
  *concurrently* (StructureUtils spaces them in a grid), and this mod drives the
  world at the **absolute** `ORIGIN` arena rather than GameTestHelper-relative
  coords, so a second `@GameTest` would collide with `agentRpcSmoke`; instead the
  YAML suite runs through `mc.test.yaml` inside the existing serial suite.
  Per-case GameTests wait on spatial isolation (`docs/yaml-gametest.md` §7.1).
  Deferred asserts (`tps`/`no_exception_in_log`/`block_changed_within`) are
  recognised but raise `UnsupportedOperationException` so a test that uses them
  fails loudly rather than silently passing.
- **`mc.world.snapshot` / `mc.world.restore` — deterministic test setup/teardown.**
  `snapshot` captures an axis-aligned box of block states *and* block-entity NBT
  into a JVM-local, named in-memory store (volume capped at 32^3; up to 64
  snapshots, cleared when the server detaches); `restore` puts the region back
  verbatim, including a chest's contents and components. This is the prerequisite
  the GameTest YAML layer (proposal §4.1 C) needs to stash a region, run a test,
  and roll it back. New `WorldApi` handler behind `DriverApi.route(...)`; restore
  emits a `world.restore` event and honors `returnEvents`. Validation script
  `33_world_snapshot.js` covers state restore, block-entity contents round-trip,
  and three-transport metadata parity — suite is now 60 GameTest cases.

### Changed
- **Internal: the pathfinder now accepts a per-intent cost bias (a `CostModifier`
  list) threaded from the `Intent` through the `Walker` into each search, appended
  after the legacy taxes — inert no-op in A4a (empty bias; byte-identical), the
  channel the LLM navigation intent layer's avoid/prefer/leash biases (A4b) ride.**
- **Internal: `mc.bot.goto` (plus the elytra ground-fallback and the replay-replan
  path) now runs on the generic `IntentProcess` (over an `Intent` value type) instead
  of the bespoke `GotoProcess` — no behavior change (A1 groundwork for the LLM
  navigation intent layer).** The old `GotoProcess` is removed; `kind()`
  stays `"goto"` so slots/status are identical; the server Avatar proof
  (`serverProcessArena`) drives the FakePlayer through `IntentProcess` and still
  ARRIVES. The GameTest run gained no new failures vs the pre-A1 tip (the terrain
  arenas drive the `Walker` directly, unaffected by the process-layer change).
- **Internal refactor: the eight per-edge pathfinder cost taxes now flow through a
  composable `CostModifier` stack — no behavior change (A0 groundwork for the LLM
  navigation intent layer).** `PathFinder.Search` seeds a `List<CostModifier>` with
  the legacy taxes (`descendTax, waterCellTax, leafCellTax, padCellTax,
  vineOverWaterTax, padOverWaterTax, climbOutTax, submergedTax`) in their original
  summation order and iterates it in the neighbor loop; `world.dangerCost` /
  `world.directionalCost` stay inline. Byte-identical (left-associative accumulation
  preserved); the GameTest suite's pass/fail set is unchanged vs the base commit.
- **Internal refactor: the five longest source files were split by responsibility
  — no behavior change.** `ToolCatalog` now concatenates per-category catalogs
  (`mcp/catalog/*`) over shared schema builders (`mcp/schema/Schemas`); the tool
  set, order, and count (45) are byte-identical. `ClientDriverApiImpl` became a
  thin facade delegating to `client/internal/*` (screen / input / chat / observe
  / screenshot). The 25 concrete pathfinder moves moved out of `Move` into
  one-class-per-file under `bot/pathfinder/moves/`. `BotApiImpl`'s client-tick
  auto-behaviors moved to `bot/auto/*` (`AutoEat`/`AutoSwim`/`AutoTool`/
  `AutoRespawn`). `DriverApi`'s `system/observe/action/wait` verb groups moved to
  sibling `SystemApi`/`ObserveApi`/`ActionApi`/`WaitApi` handlers (pure helpers in
  `ApiSupport`), while `DriverApi.route(...)` stays the single dispatch point.
  Longest file dropped from 1126 → 836 lines. All 57 GameTest cases (including
  the three-transport parity checks) stay green.

### Fixed
- **String-pulling no longer straightens a path back through danger the planner
  detoured around.** The smoother collapses flat walk/diagonal runs to straight
  segments whenever the line is *walkable* (`losWalkable`) — but it ignored
  `dangerCost`, so a route A* bowed inland to dodge (a cliff edge, a lava graze)
  got yanked straight back onto the hazard, silently undoing the avoidance (the
  raw path's `finalCost` showed the bow; the bot still walked the edge). The
  collapse is now rejected when the straight line carries more `dangerCost` than
  the original (bowed) waypoints, in which case those waypoints are kept. On safe
  ground both sums are zero, so ordinary zigzag staircases still smooth exactly
  as before — no camera-wobble regression. This is what makes the graded
  danger costs below actually reach the bot's feet instead of just the planner.
- **Dig cost is now aligned with Baritone's tick-based scale — the planner no
  longer treats one mining tick as a whole block of walking.** `breakCost`
  returned `10 × ticks`, but `10` is the cost of walking a full cardinal cell
  (vanilla 4.317 b/s → ~4.63 ticks/block, Baritone's `WALK_ONE_BLOCK_COST`), so
  every mining tick was priced ~4.6× too high. A* would take absurd detours to
  avoid breaking even a single thin wall it could have tunnelled. Costs are now
  converted with `COST_PER_TICK = 10/(20/4.317) ≈ 2.158`, putting break time on
  the same footing as travel time. The tool-speed estimate also gained the two
  modifiers vanilla applies (and Baritone counts) that the old code ignored:
  the per-tool **Efficiency enchant** (`+level²+1` once the tool beats bare
  hand) and the player-global **Haste / Mining-Fatigue** multiplier, both
  snapshotted once per search. The situational underwater / not-on-ground ÷5
  penalties are intentionally left out — they reflect the player's *current*
  stance, not where a future break happens, and omitting a slowdown keeps the
  cost admissible. The executor's `selectBestToolFor` now ranks tools by the
  same Efficiency-aware speed, so the tool it switches to matches the one the
  cost assumed. Verified live against the running build (exact to the decimal):
  a stone block costs `12.951` with a diamond pick (`2.158×6` ticks; was `60`),
  `4.317` with Efficiency&nbsp;V (`2.158×2`), and `49.645` with a wooden pick
  (`2.158×23`). The break-vs-detour decision is now tool-sensitive as Baritone
  intends: across one 15-long wall a **diamond** pick tunnels straight through
  (`traverseBreak`) while a **wooden** pick routes around the end — the slow
  tool genuinely makes the detour cheaper. Confirmed in **survival** too (the
  bot mines through a stone wall and reaches the goal at full health), not just
  creative instant-break.
- **Water-bucket (MLG) falls now land on a 1-wide column, not just a wide pwd.**
  A `fallBucket` step-off leaves the launch lip with the walk's residual
  horizontal momentum, and air has no friction, so over a tall drop the body
  coasted a full block sideways — clean off a 1-block-wide landing column. It
  then descended a *neighbouring* column with no floor, so the latch's
  straight-down placement found only the distant world floor and spawned the
  water at the world bottom (the bot survived the fall but ended up stranded at
  y≈-60, the goal unreached). The airborne MLG latch now records the planned
  landing column when it arms and, each falling tick, **bleeds the horizontal
  velocity (×0.5) plus a small clamped spring nudge toward the landing centre**
  — the two settle the body directly over the column it will place water in, so
  "straight down" hits the intended block. The nudge → 0 as the offset → 0, so
  it converges on the centre without ever pushing the bot past. Verified live
  (survival, natural regen off): drops of **10 and 20 blocks onto a strict
  1×1 landing block** both place water on the exact target cell and land at
  **full health** with the bucket scooped back, settling dead-centre (x≈31.5 on
  a block spanning 31–32). Before the fix the same 1-wide drop overshot to the
  void; a forgiving wide landing pad already worked and still does. Complements
  the `smoothLook` yaw-snap fix below (that killed *Z* drift from a lagged pan;
  this kills *forward* drift from launch momentum).
- **`mc.client.screen.info` now surfaces `causeOfDeath` on a DeathScreen.** The
  screen title is just "You Died!"; the real cause ("Player was slain by
  Phantom", "fell from a high place", …) is a separate private `Component` that
  only `screen.tree` exposed. A cheap `screen.info` probe now includes it too,
  so a caller can see *why* the bot died without walking the widget tree.
- **Leap launches (parkour / MLG fall) now snap their heading even when
  `smoothLook` is on.** The smooth camera pan (≤`smoothLookDegPerTick`/tick) is
  cosmetic for walking, but a launch into a jump or a water-bucket fall can't be
  course-corrected mid-air — a lagged smooth-panned heading sent the bot off at
  an angle, drifting sideways off a narrow landing (observed: with `smoothLook`
  on the MLG bot drifted in Z off a 1-wide landing pad, missed the placed water,
  and fell to its death). The walk actuator now hard-snaps the yaw toward the
  target for parkour and `fallBucket*` edges (functional aim, like place/break);
  plain walking still smooth-pans. Verified live: same 15-block MLG drop with
  `smoothLook` on now lands at full health with no Z drift.
- **No more render-thread stutter on big searches (time-sliced A\*).** The
  pathfinder ran to completion synchronously on the client tick, so a hard or
  far/unreachable goal could block the render thread for 50–1500 ms (visible
  frame skips). `PathFinder.Search` is now resumable: the Walker advances it by
  a `pathfinder.sliceMs` (default 6 ms) wall-clock slice per tick, so a big
  search spreads across frames — measured 6–7 ms/tick for a 2856-node search
  that was a single 54 ms block. The algorithm, node budget, and resulting path
  are unchanged (same best-effort backoff), so there's no premature "arrived" or
  quality loss; only the hitch is gone. While the first path computes, the bot
  holds instead of following a stale/empty one.
- **No more left-right camera wobble while pathing.** Three causes fixed:
  (1) the A\* path zigzags as a cardinal/diagonal **staircase**, so aiming at
  each immediate waypoint swung the heading ±10–15° — the path is now
  **string-pulled** (flat walk/diagonal runs collapse to straight segments via
  line-of-sight; vertical/parkour/break/place nodes are preserved), removing the
  staircase at the source and making the bot walk straighter; (2) the aim now
  follows an **interpolated line-of-sight carrot** a fixed distance ahead rather
  than a discrete node, so the bearing moves continuously; (3) a **pure-pursuit
  step re-sync** advances past nodes the player has already passed, so the aim
  can never flip ~180° backward when sprinting toward a far point or after a
  sliced repath starts from a now-stale position. Jump detection switched from a
  waypoint-distance heuristic to the edge's move type (so string-pulled long
  straight runs don't trigger spurious parkour jumps). Measured: a straight goto
  went from 7–9 left↔right reversals (max-step 90–179°) to **0 reversals**, a
  rock-steady heading. New gated `mc.bot.setting{walkerDebug}` traces it.
- **`mc.bot.*` movement no longer spins-and-floats when the player is in
  creative flight.** The Walker is a ground actuator (presses forward/jump,
  relies on gravity + `onGround`); while flying the player floated above the
  ground path, overshot waypoints frictionlessly, never matched a waypoint's Y
  (so the reach check never passed), kept re-aiming (the visible spinning), and
  finally timed out hovering in mid-air. The Walker now ends creative flight at
  the start of movement (`getAbilities().flying = false; onUpdateAbilities()`)
  and waits for the post-flight fall to land before pathing — scoped to a
  one-shot descent so the intentional airborne ticks of jump/parkour/fall are
  untouched, and re-applied each tick so re-toggling flight can't strand the
  bot. Verified live (hovering y=107 → descends → lands → walks to goal). New
  gated diagnostic `mc.bot.setting{walkerDebug:true}` logs the Walker's per-tick
  decisions to the `WorldDriver` logger.
- **`mc.script.eval` / RPC JSON round-trip no longer chokes on non-finite
  numbers.** `JsonCodec.encode` emitted bare `NaN`/`Infinity` (invalid JSON)
  for non-finite doubles, and `decode` couldn't read those tokens back — so a
  result carrying e.g. an unreachable-path cost intermittently failed with
  `eval payload parse error: For input string: ""`. Encode now writes `null`
  for non-finite numbers (matching `JSON.stringify`), decode accepts the
  non-standard `NaN`/`Infinity`/`-Infinity` literals (→ null) that Rhino can
  emit over wrapped Java doubles, and a genuine parser desync now throws a
  positioned `invalid JSON: expected value at N near '…'` instead of a cryptic
  `NumberFormatException`.
- **Bot no longer falls into a gap it was bridging across.** Three interacting
  causes, all found via the `walkerDebug` trace: (1) the slow sneak-crawl of a
  bridge (≈1 block / 15 ticks) left the foot block unchanged for many ticks, so
  the "stuck" counter climbed and tripped the **stuck-wiggle jump**, hopping the
  bot off the 1-wide bridge — the wiggle-jump is now suppressed while bridging
  and the stuck counter is zeroed on bridge/place edges; (2) more fundamentally,
  A\* found it *cheaper* to bridge two blocks then **parkour-leap the rest of the
  gap** (cost 92 < bridging all four = 120) — but you can't sprint-leap off a
  block you just sneak-placed, so the bot jumped from the cramped bridge tip and
  fell. Parkour moves now require a **solid real-world launch floor**
  (`Move.hasRunway`): a planned `toPlace` cell reads as air during the search,
  so A\* never chains a bridge straight into an unexecutable parkour and bridges
  the whole gap instead (this also correctly models "you need runway to
  parkour"); (3) the place actuator now also zeroes the stuck counter so a
  legitimately-slow placement isn't mistaken for being wedged.

### Changed
- **The water-bucket (MLG) clutch is now always-on, not pathing-only.** The
  whole MLG state machine — arming, the airborne place-water-on-the-impact-floor
  latch, and the post-landing scoop — lived inside `Walker.tick`, so it only ran
  while a `goto`/`mine`/etc. process was actively walking the bot. A bot that was
  idle, mining in place, or building took the full fall when knocked off a ledge.
  It's been extracted into a standalone `ClutchController` ticked at the top of
  `clientTick` (the same always-on hook as autoEat/autoSwim/autoTool), so it
  self-rescues regardless of what — if anything — is driving the bot, matching
  Baritone's clutch being a standing behaviour rather than a path step. When the
  clutch owns a descent it takes the keys and `clientTick` returns early, so an
  active process is suspended for the airborne ticks instead of fighting it. The
  planner's planned `fallBucket` falls now arm the same controller
  (`CLUTCH.armPlanned`, recording the planned landing column for drift damping)
  as the Walker steps off the lip; an unplanned damaging fall arms it reactively
  (`armReactive`, ≥ 5-block drop onto a clear MLG floor with a bucket in hand and
  no water already in the column). A planned arm that never leaves the lip (path
  changed before the walk-off) self-clears after 20 grounded ticks so it can't
  hijack a later unrelated jump. New `mc.bot.status.clutch` field
  (`idle`/`lip`/`falling`) for observing it. Verified live in **survival**: an
  **idle** bot tp'd to a 38-block drop arms reactively, places water, and lands
  at full health (the old latch could not — no process was running); and a
  `goto` across a 15-block ledge still emits `fallBucket15` and clutches through
  it (`lip → falling → idle`, full health) — no regression on the planned path.
- **`mc.action.runCommand` no longer enforces a command allow-list.** The verb
  filter (and the `-Dworlddriver.commandAllowList` system property + `DEFAULT_COMMAND_
  ALLOW_LIST` / `commandAllowed` machinery) is removed — any Brigadier verb now
  runs at operator level. The MCP/RPC transports bind to localhost, so this is a
  local/trusted-setup tradeoff; re-add a verb filter in `DriverApi.runCommand` if
  exposing the transports beyond the loopback interface.
- **`mc.bot.follow` watches its target when within range.** On arriving inside
  `radius` the bot now stops and aims at the followed entity (a tracking shot)
  instead of idling at an arbitrary heading; honors `smoothLook` (pans when on,
  snaps when off). While moving, the Walker still owns the heading.

### Added
- **Elytra firework economy — glide-and-boost sawtooth + no-overlap firing.**
  Two changes cut rocket consumption sharply with no loss of safety. (1) **No
  overlap:** a new rocket is lit only once the previous boost is fully spent
  (`boostRemaining <= 0`); the old gate (`FIRE_COOLDOWN`=12 < `BOOST_LIFE`=20)
  let a second rocket fire while the first was still burning, wasting its early
  thrust against the speed cap. (2) **Hysteretic climb band:** instead of topping
  altitude up whenever it sagged 2 blocks below target, the controller now lets
  it sag a full `CLIMB_DEADBAND`=12 blocks and ride the glide (free horizontal
  distance), boosting back up only then and holding the climb until within
  `CLIMB_MARGIN`=2 — a glide-and-boost sawtooth. Terrain avoidance is untouched:
  a climb that bleeds h-speed below `MIN_CRUISE` still triggers a boost, so the
  bot never trades collision-safety for fuel. **Verified live** on the identical
  700-block flight: firework use dropped from **32 → 6 rockets** (≈81% fewer;
  58/64 left), still **0 collisions, hp 20, landed on the goal**.
- **Elytra long-distance flight — plan-as-you-fly, no chunk cache (Baritone
  elytra-alignment milestone E).** The planner/controller only ever saw loaded
  chunks (unloaded reads as air), so a far goal meant either flooding A* or
  flying blind through terrain that hadn't streamed in yet — fine mid-range,
  unsafe long-range. Now the flight commits only to terrain it can actually see,
  and re-planning walks the route forward as chunks load — deliberately WITHOUT a
  persistent chunk cache. Three pieces: (1) **`WorldView.isKnown(pos)`** — a
  sensor distinguishing "known air" from "unloaded (unknown)"; `ClientWorldView`
  returns whether the chunk is loaded, default `true` so the ground pathfinder /
  headless tests are unchanged. (2) **Frontier sub-goal** — when the goal's chunk
  isn't loaded, the flight plans to a point at the loaded frontier along the goal
  bearing (backed off, held at a cruise altitude seeded from launch), not the far
  goal; the 40-tick re-plan marches it forward. It switches to the real goal the
  moment its chunk loads. (3) **Boost governance** — a firework is allowed only
  when the world is known far enough ahead along the heading to react
  (`knownAhead ≥ SAFETY_TICKS · speed`), so the bot can't outrun its vision and
  ram a chunk that pops in; near the frontier this throttles to a glide until
  more loads. **Verified live** (Amplified survival, render distance 12): a
  **700-block** eastbound flight over terrain entirely beyond render distance —
  552 blocks in frontier mode with the sub-goal marching 198→261→…→662 as chunks
  streamed in, altitude held (186–221) by 32 boosts, clean frontier→goal handoff
  at 143 blocks out, **0 collisions, hp 20, touched down on the goal's x,z** and
  stopped. (`elytraDebug` reactive line now also logs `boostOk`/`knownFwd`/`front`.)
- **Elytra flight now honors `smoothLook` (yaw only).** Reactive elytra steering
  set yaw/pitch directly, bypassing the `smoothLook` camera-pan toggle. The
  cruise/landing yaw now routes through `smoothAngle`, so with `smoothLook` on
  the heading pans at most `smoothLookDegPerTick` (default 20°) per tick instead
  of snapping — the visible jerk during a waypoint turn / lateral go-around.
  **Pitch deliberately stays snapped:** it's the physics input the controller's
  collision-avoidance lookahead simulated, so lagging it would desync the
  prediction (this is the exact failure the earlier pitch slew-limit experiment
  caused). During cruise the pitch hysteresis already holds Δpitch to ~0.4°/tick,
  well under the cap, so smoothing it would be a no-op anyway and only ever bite
  on a hard avoidance pull — which must never lag. Verified live in the Amplified
  survival world flying a diagonal through a real ridge (top ≈220): per-tick
  `|Δyaw|` peaked at exactly the 20°/tick cap (target demanded 35° at the
  go-around; applied yaw ramped over 3 ticks) and was a fraction of a degree in
  cruise, with **0 collisions** and full health to a grounded stop. (The
  `elytraDebug` log line now also reports `yawTgt`/`yaw`/`dYaw` for this.)
- **Elytra flight — polish: smooth camera, clearance margin, and a terrain-aware
  landing flare.** Three fixes from live mountain testing: (1) the camera no
  longer bobs up/down — the controller picked a new pitch from the discrete fan
  every tick, flip-flopping between neighbours, so a hysteresis bias toward the
  currently-held pitch keeps it steady (mean Δpitch ≈ 0.4°/tick in cruise) while
  a genuine avoidance need still switches fully in one tick. (2) The lookahead
  scoring now penalises *skimming* terrain within a 2-block clearance margin, not
  just actual collisions, so the bot keeps a buffer over ridges. (3) **Landing now actually
  lands and stops.** Previously "arrival" at a (mid-air) goal just released the
  process while the bot was still fall-flying at cruise speed, so it coasted
  uncontrolled for *hundreds* of blocks into whatever lay ahead — the real cause
  of "it flew into a mountain." The landing/abort-glide now (a) run through the
  same collision-aware lookahead (fireworks off, speed-bleed biased) so the flare
  picks e.g. a hard climb to clear a wall instead of ramming it, and (b) descend
  to the surface beneath the goal and only finish once grounded or low-and-slow,
  so it no longer coasts away. Verified live in an Amplified (tall-mountain)
  survival world: a flight whose goal sat past a 158-block summit chose a −45°
  climb-over at the flare (0 collisions), and a goal over land came to rest on
  the ground at full health. **Known limitation:** a goal over *deep open water*
  has no safe landing — the bot settles onto the surface but then sinks and
  drowns (autoSwim doesn't pull it back up reliably from a fast descent). Land
  goals (the mountain-flight use case) are the supported target; mid-ocean
  landings are out of scope for now.
- **Elytra flight — landing flare + failsafes + clutch synergy + ground
  fallback (the layer that makes it safe to actually use).** The reactive flight
  now ends in a proper landing: on final approach to the goal it flares (noses up
  to bleed horizontal speed) and settles in instead of coasting past — live, a
  flight that used to overshoot the goal by ~120 blocks now stops ~1 block from
  it, bleeding from cruise (~1.7 b/t) to a near-hover (~0.16 b/t) right over the
  target. Two failsafes abort to a gentle glide-down (nose up, no boost) rather
  than risk a kill: **durability** — bail before the elytra's `maxDamage−15`
  break point so a wing can't snap mid-air; and **stall** — no progress toward
  the goal for ~120 ticks (e.g. need to climb but out of fireworks) gives up
  gracefully. **Clutch synergy:** the always-on water-bucket clutch already
  stands down while `isFallFlying()`; when flight ends airborne (wing broke,
  stripped, ran out of room) the process just releases and clientTick's reactive
  clutch arms on the resulting fast fall — live-verified by stripping the elytra
  mid-flight in survival at y=105, after which the bot fell 165 blocks and landed
  at **full health** (clutch placed water, then scooped it). **Ground fallback:**
  `mc.bot.elytraFly{pos, groundFallback:true}` walks to the target via the normal
  pathfinder when there's no usable elytra instead of failing. All four verified
  live (ScriptTest). Aborts surface on the `elytra` slot's `lastError`.
- **Elytra flight — coarse 3D path planner (waypoint corridors around big
  obstacles).** The reactive controller below only sees one horizon ahead, so it
  can climb a ridge but can't decide to fly *around* a barrier too tall to clear
  or longer than its sightline. `ElytraPathfinder` adds the global layer: a
  bounded A* over a coarse air-voxel grid (cells `GRID`=4 apart, a node "free"
  only if a clear box surrounds it, edges kept only when the gap between free
  nodes is clear too) finds a corridor from the current position to the goal,
  then string-pulls it (collapse any run the straight line sees through) to a few
  line-of-sight turn points. Open sky short-circuits to a direct goal with no
  search. The flight follows the waypoints — steering the reactive controller at
  the current one, advancing on proximity or when the next is already in
  sight — and re-plans every 40 ticks so newly loaded terrain refines the route
  (far/unloaded chunks read as free at plan time; the controller's live lookahead
  handles whatever is really there). Status exposes the corridor length and the
  current waypoint index under the `elytra` slot. **Verified live (creative,
  ScriptTest):** against a 120-block-tall, 40-wide wall straddling the straight
  line to the goal, the planner returned a waypoint just past the wall's end and
  the bot flew *around* it at y≈−3 (clearing the z=20 end by ~3 blocks, no climb),
  then collapsed to a direct route once past — where the reactive controller
  alone would have tried to climb the wall. Same `mc.bot.elytraFly{pos}` entry;
  no new params.
- **Elytra flight — reactive sim-lookahead controller (`mc.bot.elytraFly{pos}`).**
  Building on the validated simulator below, `ElytraController` flies the bot to
  a 3D target by *forward simulation* rather than reacting to the current frame:
  each tick it rolls a fan of candidate pitches forward over a ~30-tick horizon
  with `ElytraPhysics` (yaw fixed at the goal bearing), raytraces each predicted
  trajectory against terrain (`WorldView.isSolid`, sampled so a fast tick can't
  tunnel a thin wall), and picks the pitch whose path comes closest to the goal
  without flying into a block — so the bot starts pulling up ~45 blocks before a
  ridge instead of smearing into it. Glide can only lose altitude, so when the
  goal is overhead or horizontal speed bleeds too low the controller lights a
  firework (rate-limited) and simulates that tick's candidates *with the boost
  active*, tracking the rocket's remaining life so subsequent lookaheads stay
  honest. Triggered by `mc.bot.elytraFly{pos:{x,y,z}}` (no fixed `pitch`); the
  fixed-pitch test glide and the always-on clutch's fall-flying skip are
  unchanged. **Verified live (creative, ScriptTest):** from a standing start it
  rocket-climbed +35 and steered 157 blocks to arrive within 3 blocks of a far
  higher goal (99 ticks); and against a 75-block-tall wall straddling the path it
  climbed (pitch −45) to clear the top by ~3 blocks exactly at the wall, then
  dived back to the goal altitude — no crash. Known follow-ups for later
  milestones: the score rejects only actual collisions (no clearance-margin term
  yet, so it skims obstacles) and there's no landing flare yet (the bot coasts
  past the goal on release) — those land in the planner / landing milestones.
- **Elytra flight — foundation: a tick-exact physics simulator, a takeoff +
  firework actuator, and an `mc.bot.elytraFly` verb** (milestone A of aligning
  the bot's movement with Baritone's elytra capability). The new pure simulator
  `bot/elytra/ElytraPhysics` reproduces the MC&nbsp;1.21.1 fall-flying glide
  (`LivingEntity.travel`) and firework boost (`FireworkRocketEntity.tick`)
  bit-for-bit — `glideStep`/`fireworkBoost`/`lookVec`/`stepTick`, no world, no
  side effects — so the upcoming reactive controller can simulate candidate
  pitches forward before committing. `ElytraProcess` (status under a new
  `elytra` slot) is the input layer that drives it: it takes the bot off the
  ground (jump → `tryToStartFallFlying` + `START_FALL_FLYING`) or straight out of
  a fall, holds a heading, and optionally fires rockets for boost. The always-on
  water-bucket clutch now skips while `isFallFlying()` so it can't hijack a glide
  as a "fall". With `mc.bot.setting{elytraDebug:true}` the process validates the
  simulator tick-by-tick against the live client (predicted vs observed
  `deltaMovement`) and logs per-tick + summary error. **Verified live (creative,
  ScriptTest; the fall-flying glide branch is gamemode-independent):** level,
  +30° dive, and −25° climb flights each ran 120 samples at **meanErr = maxErr =
  0.0000 blocks/tick** (every branch — gravity, dive-redirect, climb, steering,
  drag); ground takeoff jumped/deployed and a firework-boosted −12° climb gained
  **+42 blocks** from y=−60 with no takeoff error. Params: `pitch` (MC sign,
  + dives/accelerates), `yaw`/`pos` (heading or aim-at-target with `stopXZDist`),
  `fireworks`+`fireworkEveryTicks`, `ticks` cap. Route `mc.bot.elytraFly`
  (`awaitMs`-pollable). The reactive sim-lookahead controller, 3D LOS planner,
  and landing/failsafe layer build on this in the milestones that follow.
- **Severity-graded danger costs — lava ≫ fire, plus cliff-edge and contact-plant
  avoidance.** The A* soft-danger model (`WorldView.dangerCost`, gated by
  `avoidDanger`) used to add one flat `dangerPenaltyPerCell` for *either* lava or
  fire in the cell's neighbour ring and nothing else — a binary "near a hazard?"
  nudge. It's now graded by how much each hazard actually hurts, closer to how
  Baritone weighs them:
  - **Lava** gets its own, much heavier `pathfinder.lavaDangerPenalty` (default
    `80` vs fire's `30`): lava contact is lethal and keeps burning after you step
    off, so the planner pays a real detour rather than skim one block from it,
    while still threading a lava-lined corridor that is the only route.
  - **Contact plants** (cactus, sweet-berry bush, wither rose, magma block,
    powder snow) — previously only hard-rejected as the cell you'd stand *in* —
    now add a small `pathfinder.contactDangerPenalty` (default `12`) when
    adjacent, so the bot stops hugging them when an equal route exists.
  - **Cliff / void edges** get a new `pathfinder.ledgeDangerPenalty` (default
    `15`, min drop `pathfinder.ledgeDangerMinDrop` = `4`): a stand cell on the lip
    of a tall drop is mildly penalised, so the planner prefers an equal-length
    interior route — "rather detour than graze the edge" — without forcing a
    detour around every ledge or blocking a narrow bridge that is the only way. A
    drop *into water* doesn't count (safe splash).

  All five are live-tunable via `mc.bot.setting` and surfaced in the settings
  snapshot. Verified live with an in-build A/B over a real void: with the ledge
  penalty off the bot walks straight along the cliff edge; switched on, the
  committed path bows one block inland for the whole traverse, touching the edge
  only at the unavoidable start/goal cell. Lava avoidance confirmed too (the bot
  routes around a single lava cell, paying a ~1-block detour instead of the
  +80).
- **Reactive emergency water-bucket clutch — Baritone-style fall failsafe.** The
  MLG latch used to fire only on a *planned* `fallBucket` edge (the planner chose
  to descend a sheer drop). Now, even with no such edge, if the bot is plummeting
  toward a damaging impact and still holds a water bucket, the same latch arms
  itself mid-fall and self-rescues — covering an **unplanned** fall the planner
  never chose: knockback off a ledge, the ground broken out from under it, a tp,
  or a plain `Fall` edge whose drop turns out to hurt. Each airborne tick (during
  an active goto) it checks: falling (Δy < −0.4), a water bucket in the hotbar, a
  full placeable `isMlgFloor` below within 64 blocks, the remaining drop
  > `EMERGENCY_CLUTCH_MIN_DROP` (5 — tall enough to deal real damage, with room
  left to place), and no existing water in the column (so it never fights a
  `FallIntoWater` descent or wastes the bucket when water already breaks the
  fall). When all hold it arms `mlgArmed` over the current column and the existing
  descent-owner places water + scoops on landing. Gated on the same
  `allowWaterBucketFall` capability (the bot may spend its bucket to break a
  fall), so enabling planned MLG falls now also enables the failsafe. Required a
  companion fix: a mid-air foot has no walkable neighbours, so an unplanned fall
  made A\* repath to "no path" and the goto process **terminated** — which stopped
  ticking the Walker and the clutch never ran; the no-path branch now **holds
  (stays airborne, keeps ticking) instead of failing while off the ground**, then
  repaths normally once landed. Verified live (survival, natural regen off): a bot
  walking a floor, tp'd 44 blocks into the air mid-walk, **arms the clutch, places
  water, and lands at full health** with the bucket scooped; negative control —
  same fall with `allowWaterBucketFall` off — dies ("fell from a high place"),
  confirming the clutch is what saves it. **Boundary:** the clutch only runs while
  a goto is active (the Walker ticks only then) — an idle, non-pathing bot knocked
  off a cliff is not yet covered; a fully always-on net would need the MLG state
  machine extracted to a standalone client-tick hook.
- **Parkour descend — Baritone `MovementParkour` lower-landing parity.** A\* can
  now sprint-jump a 2-block cardinal gap and land **one block lower** (a new
  `ParkourDescend` move), closing the last same-gap direction: the catalog could
  already leap a gap flat (`Parkour2/3`) or up (`ParkourAscend`), but a gap whose
  far side sat *lower* had no move (`Parkour2/3` require a same-Y landing, `Fall`
  only drops straight down one horizontal block), forcing a long detour. Drops
  stay ≤3 so there's never fall damage. The reliably-landing case —
  `parkourDescend2d1` (2-gap, 1 down) — is always in the catalog; deeper drops
  (drop ≥2) and the longer dist-3 gap carry the bot horizontally past a 1-wide
  pad before touchdown, so they ride the `allowParkour4` "marginal physics" gate
  (the same tier and honesty as the dist-3 ascend). The move reuses the existing
  parkour actuator (name starts with `parkour`), plus a **landing brake**: a
  descending leap touches down with more forward momentum than a flat one, so
  once airborne and within ~1.2 block of the landing center the Walker cuts
  forward+sprint and holds sneak (ledge-guard) to stop the bot **on** the block
  instead of sliding off the far edge — the arrival and step-advance guards also
  hold while airborne on a `parkourDescend` edge (mirroring parkour-place) so the
  brake owns the touchdown. Verified live: A\* planned `parkourDescend2d1` over a
  2-gap and the bot landed on the 1-wide lower block at full health and stayed
  put (steady 60 ticks); without the brake it overshot and fell to its death. A
  drop-2 gap reports `no path` with `allowParkour4` off and is planned with it on.
- **Parkour ascend — Baritone `MovementParkour` +1-landing parity.** A\* can now
  sprint-jump a 2–3 block cardinal gap and land **one block higher** than the
  launch (a new `ParkourAscend` move), instead of only flat leaps. A 1-block
  ascend is already a `StepUp`, and ascends taller than 3 aren't reachable by
  vanilla sprint-jump physics, so only distances 2–3 are enumerated. Because the
  rising body sweeps a taller box than a flat parkour, each gap column is
  required clear **3 tall** (foot, head, head+1 — a y+2 ceiling clips the apex),
  with no stand-able floor at launch level (else a cheaper Walk/StepUp chain
  wins). Distance-2 is a reliable vanilla leap and is always in the catalog like
  `Parkour2`/`Parkour3`; distance-3 (clearing 3 while rising 1) is at the physics
  edge, so it shares the `allowParkour4` gate with the other marginal long leaps.
  Cost is flat-parkour + 5 (the jump-up overhead, as in `StepUp`): 27 and 37. No
  new config/WorldView surface, and no actuator change — the `parkourAscend` move
  name starts with `parkour`, so the Walker's existing parkour actuator (jump +
  sprint, yaw snapped, aim at the destination) drives it unchanged. Verified
  live: A\* planned `parkourAscend2` over a 2-gap and the bot landed on the +1
  ledge (`finalCost 27`); for a 3-gap, the goto reported `no path` with
  `allowParkour4` off and planned `parkourAscend3` (`finalCost 37`) with it on —
  confirming the move and its gate. The distance-3 +1 leap is beyond plain
  sprint-jump reach (clearing 3 while rising 1 needs jump-boost/Speed), so in the
  unboosted test the bot attempted the planned leap and fell — exactly the
  `allowParkour4` "marginal physics" contract it shares with flat `Parkour4`.
- **Fall into existing water — Baritone fall-/descend-into-water parity.** A\*
  can now step off a ledge and drop further than the 3-block no-water cap when
  the landing cell already holds water (a new `FallIntoWater` move for drops
  4–20). Entering a water block negates *all* fall damage in vanilla regardless
  of height, so — unlike `WaterBucketFall`, which must place and scoop its own
  source — this needs **no bucket and no config gate**: it's a pure, item-free
  movement move always in the catalog, like the dry `Fall` (which already covers
  drops ≤3 into water). `valid()` short-circuits on a single `isWater(to)` read,
  so a dry column pays almost nothing for the ~17 extra enumerated heights; cost
  is `10 + 4·drop` (well under a same-height water-bucket fall, since there's no
  place/scoop overhead). The `fallWater*` move name keys the Walker to step off
  **near-vertically (no sprint, yaw snapped)** — a tall drop's longer airtime
  would otherwise let sprint momentum carry the bot horizontally past the narrow
  water column — without arming the MLG water-placement latch (there's no source
  to place here). Verified live: A\* planned `fallWater10` off a ledge into a
  10-block-deep pool and the bot landed at **full health** (zero fall damage);
  draining the pool makes the same goto report `no path` (dry `Fall` caps at 3
  and `WaterBucketFall` is gated off), proving the water is what enables it.
- **Parkour-place — Baritone `allowParkourPlace` parity.** A\* can now cross a
  2-block gap with a single sprint-jump onto a block placed *mid-air* during the
  leap (a new `ParkourPlace` move, gated behind `mc.bot.setting{allowParkourPlace}`,
  off by default like `allowPlace`), instead of the slow two-step sneak-bridge.
  It only fires when the landing cell has no floor of its own **and** has a
  pre-existing solid neighbour to place against (`WorldView.canParkourPlace()` +
  a new `Move.hasPlaceSupport` gate) — you can't place a floating block over open
  void, and Baritone gates on the same `canPlaceAgainst`; otherwise A\* falls back
  to `BridgePlace`. Cost 42 (parkour + place), so a real walk/bridge wins when it
  exists. The leap is a Walker concern in two phases: **leap** preserves the
  approach run-up (forward+sprint+jump off the lip) and places the landing block
  the instant a support is in reach (synthetic `clientUseItemOn` hit — the
  crosshair stays on the destination, no down-aim needed); **settle** then drops
  sprint and holds sneak so the leap momentum doesn't carry the bot off the fresh
  1-wide block into a gap beyond (sneak's ledge-guard stops it on the block).
  Arrival/step-advance hold while airborne on a `parkourPlace` edge (mirroring the
  pillar case) so the settle brake runs before "arrived". Verified live: A\*
  planned `parkourPlace2` across a 2-gap to a landing with only a *below*-type
  support (void beyond), placed the floor mid-leap, and stopped **on** the block
  at full health; the same goto with `allowParkourPlace` off reports `no path`.
- **Water-bucket (MLG) falls — Baritone `maxFallHeightBucket` parity.** A\* can
  now descend a sheer drop taller than the 3-block no-water cap (a new
  `WaterBucketFall` move for drops 4–20, gated behind
  `mc.bot.setting{allowWaterBucketFall}`, off by default like `allowPlace`)
  by stepping off the ledge, placing a water source on the landing to break the
  fall, then scooping the bucket back. Requires a water bucket in the hotbar;
  the catalog enumerates drops up to 20 and each gates on the live
  `maxWaterBucketFall` cap (default 20) + bucket availability via a new
  `WorldView.canWaterBucketFall()` (cached once per search in `beginSearch`, so
  the ~68 added candidate falls stay cheap). The move is a pure-movement edge —
  the MLG execution is a Walker concern: as the bot steps off the lip it **kills
  sprint** (so it drops near-vertically) and **latches** an MLG-fall state that
  then owns the whole descent, independent of the per-tick A\* path. Each
  airborne tick it looks straight down, finds the real impact floor under its
  *current* column, and the moment that floor is within reach right-clicks the
  water bucket (via `Item.use`, the bucket's POV-raycast placement — *not*
  `useItemOn`, which is a no-op for buckets) to spawn the source it falls into;
  on landing it scoops the source back from its feet cell (`waterBucketScoop`,
  on by default) so the bucket is reusable and the world left clean. The latch
  is essential: A\* relabels the edge to a plain `fall3` as soon as a regular
  landing comes within 3 blocks of the falling body, so dispatching on the edge
  label alone silently stopped placing water mid-fall and the bot ate the full
  drop. The move also only targets a **full, non-waterloggable** landing floor
  (`WorldView.isMlgFloor`): on a trapdoor/slab/stairs the bucket would waterlog
  the block instead of filling the landing cell, and on an end rod / partial
  block there's no flat surface to land on — so A* refuses the MLG there (no
  path → the bot stays safe) rather than diving to its death. Verified live
  (survival, **natural regen disabled** so readings are real,
  with `smoothLook` on): drops of 4 / 10 / 15 / 20 blocks each reached the goal
  at **full health — 0 fall damage** with the water scooped back and none left
  behind; negative test — drop 21 (> `maxWaterBucketFall`) reports `no path` and
  the bot stays safely on top.
- **Diagonal ascend / descend moves — Baritone `MovementDiagonal` Y-delta
  parity.** A\* can now cut the corner of a staircase in a single move
  (`DiagonalAscend` `(±1,+1,±1)` cost 19, `DiagonalDescend` `(±1,−1,±1)` cost
  14) instead of zig-zagging a cardinal `Walk`+`StepUp` / `StepDown`+`Walk`
  pair. Clearance is stricter than a flat diagonal — a rising/falling body
  sweeps the whole corner, so **both** cardinal side columns must be clear (not
  just one). Costs keep the octile heuristic admissible (a (1,1,1) displacement
  credits 14). Executes with no Walker change (ascend jumps because the waypoint
  is higher and aims at it; descend walks off the corner). Verified live: the
  bot climbed a 1-wide diagonal stone staircase end-to-end (y 151→156, five
  ascends — the only physically possible route up it).
- **Chained multi-block bridging — Baritone `allowPlace` traverse parity over
  wide gaps.** `BridgePlace` no longer re-checks the *static* world for a solid
  support under the bot's feet (the same fix `PillarUp` already carries): every
  node A\* reaches has a real-or-placed block beneath it, and that block is a
  horizontal neighbour of the gap floor we place, so the Walker's place actuator
  always finds a face to click. Previously a single bridge could only reach one
  block out from solid ground (bridge #2's support is the block #1 just placed,
  still seen as air during the search); now the bot bridges a whole chasm one
  sneak-placed block at a time. The Walker **sneaks and doesn't sprint** while a
  bridge edge is current/next, so a sprint overshoot can't carry it off the
  1-wide block into the gap. Verified live: bot bridged a 4-wide deep chasm
  (placed all four floor blocks 313–316) and stopped exactly on the far
  platform. Needs `mc.bot.setting{allowPlace}` + a placeable hotbar block
  (creative skips the inventory check).
- **Danger-avoidance cost field in A\* — Baritone avoidance parity.** Beyond the
  existing hard `isHazard` reject (which makes lava/fire impassable), the
  planner now adds a *soft* cost for standing in a cell adjacent to lava/fire
  (`WorldView.dangerCost`, summed over the 12 face-neighbours at foot/head/below
  level × `dangerPenaltyPerCell`, default 30). Routes keep a one-block buffer
  from hazards when a safe alternative exists, yet still thread a lava-lined
  corridor when it's the only way (it's a penalty, not a wall). The penalty is
  added to A*'s `g` per entered cell, so it stays admissible. **On by default**
  (`mc.bot.setting{avoidDanger}`) since it only makes routes safer; tune via
  `pathfinder.dangerPenalty` `[0,1000]`. The headless GameTest view inherits the
  `dangerCost`-returns-0 default, so CI is unaffected. Validation:
  `32_break_place.js` (toggle + range round-trip; live lava-pool routing
  verified over MCP).
- **Mob-proximity avoidance — Baritone `Avoidance` parity.** `avoidMobs`
  (off by default; changes pathing noticeably) makes A* add a distance-ramped
  cost near hostile mobs so routes give creepers/zombies a berth when they can.
  The per-node cost is cheap because hostile mobs are snapshotted **once per
  search** via the new `WorldView.beginSearch()` hook (the entity scan would be
  far too costly per node) and the penalty ramps linearly from
  `pathfinder.mobAvoidPenalty` (40) at the mob to 0 at `pathfinder.mobAvoidRadius`
  (6). Snapshot refreshes on every repath, so it tracks moving mobs. Runs on the
  client tick (entity reads are thread-safe there). Validation: `32_break_place.js`
  (toggle + range round-trip; live zombie-detour verified over MCP).
- **Break-to-move / place-to-move in A\* — Baritone `allowBreak` / `allowPlace`
  parity.** The pathfinder can now reach goals that have no pre-existing
  walkable route: it MINES through obstructing blocks, BRIDGES one-block gaps,
  and PILLARS up to gain height as part of the route, with each action's cost
  folded into A* so a detour is preferred whenever one is cheaper. Four new
  `Move` types — `TraverseBreak` (tunnel through a wall at the same Y),
  `DownBreak` (dig straight down one and drop, with a safe-landing check),
  `BridgePlace` (place a throwaway hotbar block to span a gap, then walk on),
  and `PillarUp` (Baritone `MovementPillar` — place a block underfoot while
  jumping over it to rise one level, breaking the ceiling first if blocked).
  Break cost is tool-aware (best hotbar
  tool vs. block hardness, via the vanilla mining formula); fluids and
  unbreakable blocks are `+∞` (never chosen). The Walker grew a break/place
  actuator: on reaching the cell before an action edge it snap-aims (functional
  aim never smooths), mines `toBreak` / places `toPlace`, then walks on; a
  stall triggers a repath. Both gated behind new
  `mc.bot.setting{allowBreak, allowPlace}`, **off by default** so
  `goto`/`follow`/`explore` stay non-destructive unless opted in (livestream-safe);
  `allowPlace` additionally needs a `BlockItem` in the hotbar (creative exempt).
  The A* `Result` now carries a per-step edge list (break/place actions aligned
  to the path); pure-movement edges carry none, so the all-walk hot path is
  unchanged. The Walker's pillar actuator reuses the `construct mode:"tower"`
  jump→place timing and holds position until grounded on the new block before
  advancing. Validation: `32_break_place.js` (toggle round-trip + transport
  parity; functional tunnel / bridge / pillar verified live over MCP).
- **Camera smoothing for stream/demo** — `mc.bot.setting{smoothLook:true}`
  makes the pathfinding Walker and the `mc.bot.lookAt` verb pan toward their
  target at `smoothLookDegPerTick` (default 20°/tick) instead of snapping. When
  on, `lookAt` runs as a cancellable `look` process (pos-tracking or fixed
  yaw/pitch) that converges over ticks; off (default) keeps the instant,
  process-free behavior. Functional aiming that gates an immediate raycast —
  attack, place, break, build face — always snaps, so smoothing never makes
  those actions miss. Validation: extended `19_setting_survival.js`.
- **Baritone goal-surface parity** — the `Goal` catalog now mirrors every
  `baritone.api.pathing.goals` type, re-expressed in this project's cost units:
  `GetToBlock` (stand beside/above/below a block — chests/furnaces),
  `TwoBlocks` (stand inside at foot or eye level), `Axis` (reach the nearest
  world axis/diagonal at `axisHeight`), `Inverted` (flee a goal), and
  `StrictDirection` (bore one cardinal with no fixed endpoint). Reached via new
  `mc.bot.goto` forms: `axis:true`, `goalMode:"in"/"two"/"adjacent"`,
  `direction+strict:true`, and `invert:true` (no new tools — `goto` absorbs
  them). New tunable `mc.bot.setting{pathfinder.axisHeight}` (Baritone
  `axisHeight`, default 120). Validation: `31_goal_types.js`.
- **Pathfinder A\* aligned to Baritone's method.** The best-effort fallback now
  uses **incremental cost backoff** (track the best node under a spread of
  g-vs-h weightings, commit to the most conservative candidate that travelled
  ≥ `MIN_DIST_PATH` = 5 blocks) instead of naively returning the single
  lowest-h node — successive segment ends give long-distance splicing for free.
  Node repropagation gained a **minimum-improvement** gate (Baritone's
  0.01-tick rule) for when fractional move costs land.
- `LICENSE`, `CONTRIBUTING.md`, `CHANGELOG.md`, and agent-instruction files
  (`AGENTS.md`, `CLAUDE.md`) at the project root.
- **MCP tool catalog expanded** — total now **45 tools** across nine groups
  (was 18 at v0.1.0):
- **`mc.client.chat.history` + `chat.send{awaitReplyMs}` + `mc.client.overlays`
  — fills the "agent can't see what the server said back" gap** (Phase D7).
  `chat.history{limit?, sinceSeq?}` reads the local `ChatComponent.allMessages`
  scrollback (system + player) via cached reflection so command feedback like
  `Gave 64 [Cobblestone] to Player` and advancement toast text are now
  observable. `chat.send` grew an optional `awaitReplyMs:1..30000` — after
  dispatching, it polls the chat tail and folds the next inbound message into
  the response as `{reply:{seq,text,ageTicks}}`, removing the act→sleep→history
  round-trip. `mc.client.overlays{tutorial?:bool=true, toasts?:bool=true}`
  dismisses persistent HUD overlays — sets `Options.tutorialStep=NONE` +
  `Tutorial.setStep(NONE)` so vanilla stops drawing "Move with W,A,S,D" /
  "Look around" / "Use mouse to turn" (which never naturally clear under Xvfb
  since no mouse events fire), and clears the `ToastComponent` queue
  (advancements, recipes). Prelude exposes `Agent.client.chat.send/history`
  and `Agent.client.overlays`.
- **`BackfillProcess` — Baritone analogue auto-fills cells the bot walked through**
  (Phase D8). New `mc.bot.setting` keys: `autoBackfill:bool` (default false),
  `autoBackfillBlock:id` (default `minecraft:cobblestone`),
  `autoBackfillRadius:[1,16]` (default 6). When the setting is on, every
  `clientTick` records the player's foot block to a 512-entry LRU
  `BackfillTracker`. Whenever no other process holds the slot AND the tracker
  has candidates (air cells with a solid neighbor within the radius, not the
  player's own foot/head), the bot auto-starts a `BackfillProcess` that picks
  the nearest candidate, pathfinds adjacent, sneaks, approach-centers, then
  places the configured block — same placement loop as the post-Baritone-study
  `BuildProcess`. Self-terminates when the queue empties. Reuses the
  `builder` status slot. Realistic live test: a 1×2 corridor carved through
  a solid stone mountain, with the bot stepped through in three 2-3-cell
  segments (autoBackfill on between segments). Result: cells (1,67), (4,67),
  (7,67) sealed with cobblestone — exactly the cell directly behind each
  idle-point. Ceiling cells (y=68) intentionally left untracked so the bot
  keeps headroom. The bot can only seal cells reachable from its current
  position; once a cell is sealed, the corridor behind it becomes unreachable
  (same structural limitation Baritone's BackfillProcess has unless the bot
  is continuously mining forward). Prelude exposes
  `Agent.bot.autoBackfill(on, {block,radius})`.
- **`BuildProcess` self-blocking fix — Baritone-aligned sneak + approach-center**.
  Three layered fixes after diagnosing why a 2×2×1 schematic placed only 1/4
  blocks live: (1) `findStandableNear` filters cells whose foot/head AABB
  would intersect the target. (2) `clientUseItemOn` no longer
  `setShiftKeyDown(false)` unconditionally — that was undoing
  `BuildProcess.PLACING`'s sneak right before `MultiPlayerGameMode.useItemOn`
  evaluated `Level.isUnobstructed(state, pos, CollisionContext.of(player))`,
  flipping the player's collision context back to the standing AABB.
  `SleepProcess` (the only legitimate non-sneak caller — bed right-click
  refuses while crouching) now releases shift explicitly. (3) `PLACING` gates
  the click on `horizD < 0.25` from `currentStand` center: `Walker.REACH_DIST_SQ=0.45`
  lets arrival land ~0.4 short of the stand cell, and with the sneaking AABB
  half-width 0.3 that leaves only ~0.19 clearance from the placement target's
  edge — vanilla's collision check rejects. The new gate holds `keyUp` and
  re-aims yaw toward stand-center until clearance is achieved, then sneaks
  and clicks. Live retest: 4/4 placed in 3.2 s where prior code consistently
  placed only the one entry whose target was already adjacent to the player's
  natural arrival cell.
- **`mc.bot.construct{mode:"tower"|"bridge"}` — Baritone pillar + bridge folded
  into one verb** (Hard Rule #6 — one new tool, two Processes internally).
  `mode:"tower"` pillars straight up: each cycle ensures a placeable block in
  hand, presses jump, waits ~3 ticks for the player to clear the destination
  cell, faces down, fires `useItemOn(support, UP)`; player lands on the new
  block; repeat until feet reach `height`/`targetY` (span capped at 256).
  `mode:"bridge"` sneak-walks in a chosen cardinal (forward/back/left/right
  snap to nearest yaw cardinal); on every edge the next-cell-down has no
  support, stops walking, faces the forward face of the current support and
  fires `useItemOn(support, forwardFace)` to extend the bridge; resumes
  walking once the new support is solid (distance capped at 64). Both reuse
  the `builder` status slot. Optional `block:"id"` picks a specific stack;
  default auto-selects the first BlockItem in hotbar. Stops on no-block,
  target reached, or stuck (no Y/XZ gain in 60–80 ticks). Prelude exposes
  `Agent.bot.construct(opts)` plus thin aliases `Agent.bot.tower(opts)` and
  `Agent.bot.bridge(opts)` that pre-fill `mode`. `releaseKeys()` now also
  clears `keyShift` + the logical sneak flag so BridgeProcess cancellation
  doesn't leave the player crouched.
- **`mc.bot.sleep` — Baritone `SleepBehavior` analogue**. Scans loaded chunks
  for the nearest `BlockTags.BEDS` block within `radius` (default 16, max 64),
  pathfinds to a `Goal.Near(bed, 2)`, faces, right-clicks. Pass `pos:{x,y,z}`
  to target a specific bed (skip the scan). Vanilla owns all sleep gating
  (must be night or thunderstorm, no nearby hostile mobs, bed not already
  occupied); rejections surface as `goto.lastError` on the next `mc.bot.status`
  tick. Process completes once `LocalPlayer.isSleeping()` or after a ~2s
  USE-phase timeout. Reuses the `goto` status slot since walking is the
  dominant phase — no `BotState` schema bump.
- **Baritone-aligned bot surface** — `mc.bot.goto` accepts new Baritone-style
  selectors: `block:"id"` (nearest matching block within radius), `entity:"type"`
  / `entityId:N` (track an entity), `direction:"forward|back|north|..."` +
  `distance:N` (Baritone `thisway` / `tunnel`), `waypoint:"name"` (saved
  position). `mc.bot.waypoint` (new tool) saves/lists/gets/deletes named
  in-memory positions. `mc.bot.setting` gains `autoEat` (hold useItem on a food
  item while food≤threshold), `autoRespawn` (auto-click DeathScreen Respawn),
  `autoEatFoodThreshold`, `pathfinder.maxNodes`, `pathfinder.maxMs`. All
  selectors fit existing tools — only `waypoint` justified its own surface.
- **`mc.bot.clearArea` Baritone sel-system parity** — same tool now handles
  `clear` (default), `fill:'id'` (break + place each cell), and
  `replace:{from,to}` (only act on matching cells, leave 'to' behind). Cap
  stays 4096 vol; fill/replace need the block in inventory. One unified
  `BboxFillProcess` replaces the old `ClearAreaProcess`.
- **`mc.bot.setting{blocksToAvoid:[id,...]}`** — Baritone `blocksToAvoid`
  parity. Pathfinder treats these as hazards in addition to the built-in
  set (lava/fire/magma/cactus/sweet-berries/powder-snow/wither-rose). Whole-
  list write; invalid ids reject the entire write.
- **PathFinder `Parkour2` Move** — 2-block cardinal leap at same Y over a
  real gap (no stand-able cell between). Cost 22 so plain walking always
  wins when valid. Walker auto-jumps when next waypoint is ≥1.8 horiz at
  same Y. Baritone `allowParkour` analogue.
- **`mc.bot.setting{autoSwim:true}`** — holds jump while fully submerged so
  the bot rises to the surface rather than drowning. Yields to active
  walker processes that own keyJump.
- **`mc.bot.status.lastPath`** — surfaces the most recent A* result
  ({expanded, ms, goalReached, finalCost, pathLen}) so callers can debug
  pathing failures (low expanded + goalReached=false = unreachable goal).
- **PathFinder `Parkour3` + `Parkour2Diagonal` Moves** — Baritone parkour
  set rounded out: 3-block cardinal leap (sprint-jump max, cost 32) and
  2-block 45° diagonal leap that bridges inside L-corners (cost 33). Same
  gap-requirement as `Parkour2` (no stand-able floor under the air column)
  so a Walk-chain alternative wins when valid. Walker's parkour heuristic
  (`horizD > 1.8` at same Y → hold jump) naturally covers all three sizes.
- **`mc.bot.farm`** — Baritone `farm` analogue. New tool (justified — new
  verb, no existing tool covers harvest-and-replant). Walks a 2D bbox
  (≤4096 XZ cells), scans for mature `wheat` / `carrots` / `potatoes` /
  `beetroots` (detected via `CropBlock.isMaxAge`), breaks each, then holds
  useItem on the farmland with the dropped seed in hand. `replant:false`
  to harvest-only; `crops:[...]` to restrict the set. Status surfaces under
  the `builder` slot (same as `clearArea`/`build`).
- **PathFinder `Parkour4` Move + `setting{allowParkour4:bool}`** — Baritone
  `allowParkour4` analogue. 4-block cardinal leap (cost 42) gated by the
  toggle (default off — leap is at the edge of vanilla sprint+jump physics
  and usually needs jump-boost / Speed to land cleanly). Gate is checked
  inside `Parkour4.valid()`, so A* simply never emits it when the toggle is
  off — no expansion-budget impact.
- **`setting{autoTool:bool}`** — Baritone `autoTool` analogue. When the
  crosshair points at a breakable block and no bot process owns hotbar
  selection, swap to the hotbar slot with the best destroy speed (prefers
  correct-tool-for-drops). Default off so scripted hotbar layouts aren't
  fought tick-to-tick.
- **PathFinder `Parkour3Diagonal` Move** — 3-block 45° diagonal leap (cost
  47), gated behind the same `allowParkour4` toggle as `Parkour4` since
  the horizontal reach (~4.24 blocks) is at the same edge of vanilla
  physics. Conservative validity check sweeps the entire 2×2 corner column
  at foot+head before emitting.
- **`Agent.bot.tunnel(opts)` prelude helper** — Baritone tunnel without a
  new MCP tool. Reads `mc.observe.player`, snaps yaw to nearest cardinal
  (for `forward`/`back`/`left`/`right`), computes the corridor bbox, and
  dispatches `mc.bot.clearArea`. Accepts absolute compass directions too
  (`north`/`south`/`east`/`west`/`up`/`down`). Optional `fill:'id'`
  forwards to clearArea's fill mode for instant-bridge corridors.
- **`mc.bot.build{schematicBase64}` — Sponge .schem (v1/v2/v3) loader**.
  Accepts a base64-encoded `.schem` payload alongside the existing
  procedural `schematic` object (mutually exclusive). NBT decoded with
  `NbtIo` (auto-detects gzip vs raw), Palette→base block id mapping
  strips state suffixes, varint-packed BlockData unpacked in X→Z→Y
  order. BlockEntities and biomes are intentionally dropped — BuildProcess
  only places vanilla block ids. Same 4096-block cap as procedural mode.
  - `mc.observe.player` — snapshot of player pos / look / health / hand /
    hotbar / selectedSlot. Client-MCP fallback also returns `inventory[]`,
    `saturation`, `effects[]`, `time:{dayTime,dayOfWorld,timeOfDay,phase}`,
    and the crosshair `hit` HitResult so the full state is reachable without
    opening any screen.
  - `mc.observe.container` — BlockEntity slot contents at `pos`; omit `pos`
    to read the currently open container menu (player inv / crafting / chest).
  - `mc.action.fill` — fill an axis-aligned box (≤ 32^3) in one server-thread hop.
  - `mc.action.placeMany` — place a heterogeneous list of {pos,type} (≤ 4096).
    Covers the single-block case too (`{blocks:[{pos,type}]}`).
  - `mc.wait.event` / `mc.wait.worldReady` / `mc.wait.condition` — long-poll
    primitives that block the worker thread (never the server thread) up to
    120s, with a generic invoke→field→truthy/equals matcher in `condition`.
  - 13 `mc.bot.*` client-side processes — `goto`, `mine`, `build`, `clearArea`,
    `follow`, `explore`, `runAway`, `lookAt`, `useItem` (pos optional;
    absorbed former `useItemOn`), `attackEntity` (left-click a mob),
    `cancel`, `status`, `setting` (also absorbs former `pause`/`resume` as
    `{paused:true|false}`). Long-running tasks ride an in-mod A* `PathFinder`
    (no baritone, no mineflayer); `useItem` / `lookAt` / `attackEntity` are
    instant.
  - `mc.client.input.slotClick` — Menu.clicked with explicit ClickType
    (pickup / quickMove for shift-click / throw for Q-drop / swap / clone /
    pickupAll); the only way to get shift-click without spoofing GLFW
    modifier state.
- Client-MCP fallback for `mc.query` — `q='entities'` scans `ClientLevel`
  when no server is attached and includes numeric `id` per row (suitable for
  `mc.bot.attackEntity`); `q='blocks'` scans `ClientLevel` too, with radius
  capped at 16.
- `mc.client.screen.tree` includes `causeOfDeath` when the current screen is
  a `DeathScreen` (reflectively read so the client surfaces the kill-cause
  text the player sees on death).
- Validation scripts: `11_script_eval.js`, `12_use_item.js`,
  `13_set_hotbar_slot.js`, `14_type_text_and_key.js`,
  `15_input_slot_click.js`, `16_attack_entity.js`, `17_goto_selectors.js`,
  `18_waypoint.js`, `19_setting_survival.js`, `20_clearArea_modes.js`,
  `21_blocks_to_avoid.js`, `22_phase_c.js`, `23_phase_d.js`,
  `24_phase_d2.js`, `25_phase_d3.js`, `26_schematic_loader.js`. 51 cases
  must pass.

### Fixed
- `mc.bot.build` PLACING phase now drives the real
  `MultiPlayerGameMode.useItemOn` simulation with a synthetic `BlockHitResult`
  instead of bypassing through `server.setBlock`. The earlier bypass was a
  Phase-3 expedient flagged in `BotApiImpl.java`; the synthetic
  `BlockHitResult` sidesteps the stale `Minecraft.hitResult` race that
  motivated the bypass.
- `mc.observe.eventsSince` no longer NPEs when `cursor` is omitted —
  defaults to 0 (return whatever is still in the buffer).
- `mc.bot.mine` adds a COLLECT phase after the quota is met: iterates through
  recent break positions so dropped items get picked up. Without this the
  player walked away with a counter incremented but an empty inventory.
- `PathFinder` expansion budget bumped 20000→100000 — surface-to-tree-canopy
  and other vertical traversals no longer hit "no path (expanded=20000)".

### Changed
- Tool consolidation (back-compat helpers retained in prelude):
  - `mc.bot.useItemOn` folded into `mc.bot.useItem` (pos-mode).
  - `mc.bot.pause` / `mc.bot.resume` folded into
    `mc.bot.setting{paused:bool}`.
  - `mc.action.placeBlock` folded into `mc.action.placeMany` (single entry).
  - `mc.observe.area` folded into `mc.query{q:'blocks', filter:{in_radius,type?}}`
    (client fallback preserved).
  - `mc.client.screen.openInventory` / `openPause` removed — reach them via
    `mc.client.input.key{key:'E'}` / `{key:'ESCAPE'}`, the vanilla keybind path.
- Tool descriptions trimmed ~34% (19570 → 12931 chars) for cheaper schema
  delivery to LLM clients.
- Smoke-test artifacts now land in `fabric/run/smoke/` instead of a top-level
  `smoke-shots/` directory.
- README, `README-zh_CN.md`, and `docs/mcp-clients.md` updated to reflect the
  consolidated 40-tool catalog and the fact that RPC + MCP come up at client
  init (TitleScreen-connectable), not only at `onServerStarting`.

### Removed
- Stray `*-run.log` files at the project root and the committed `smoke-shots/`
  artifacts; they are local-run outputs and should never have been tracked.
- Dead Java for the merged tools — `ClientDriverApi.openInventory` /
  `openPause`, `BotApi.pause` / `resume`, and their impls; routes call only
  the merged surface now.

## [0.1.0] — 2026-05-26

Phase 1 — perceive + act + minimal client driving — complete and verified
end-to-end.

### Added
- **DriverApi**: single source of truth (~900 lines), routes every method
  through `DriverApi.route(method, params)` on the server thread.
- **MCP Streamable HTTP server** on `http://127.0.0.1:<port>/mcp`, exposing
  18 tools across five groups (`mc.system.*`, `mc.observe.*`, `mc.action.*`,
  `mc.query`, `mc.script.eval`, `mc.client.*`).
  - Spec-conformant: `initialize` protocol-version negotiation, Origin header
    validation (loopback allowlist), MCP `image` content blocks for screenshots,
    `text+image` envelope for multimodal vision.
- **WebSocket RPC server** on `ws://127.0.0.1:<port>/rpc`, JSON-NDJSON, same
  DriverApi surface.
- **In-JVM Rhino scripting** (`dev.latvian.mods:rhino:2101.2.7-build.81`)
  - Sandboxed via `ScriptClassFilter`: blocks `Runtime`, `ProcessBuilder`,
    `Thread`, `File`, `Socket`, reflection, JDK internals.
  - `mc.script.eval` adds a wall-clock deadline enforced via Rhino's
    instruction-count observer.
- **Brigadier `/agent` subcommands**: `test`, `test list`, `test result`,
  `port`, `mcp`, `reload`.
- **Validation suite**: 11 `*.js` scripts under
  `common/src/main/resources/data/worlddriver/scripts/agent_validation/`,
  expanded into 36 GameTest cases. Asserts byte-identical results across all
  three transports.
- **Cross-platform parity**: same `common/` sources ship on Fabric (1.21.1) and
  NeoForge (1.21.1) via Architectury.
- **Project-local `.mcp.json`** at the workspace root for zero-config wiring
  into Claude Code, Cursor, Continue, Codex, MCP Inspector.
- **Docs**: `docs/mcp-clients.md` (per-client connection guide),
  `docs/claude_desktop_config.example.json`.

[Unreleased]: https://github.com/AI-assisted-Minecraft-Developers/worlddriver/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/AI-assisted-Minecraft-Developers/worlddriver/releases/tag/v0.1.0
