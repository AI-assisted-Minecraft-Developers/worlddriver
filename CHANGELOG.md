# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## 2026-08-21

- **The integrated topology's body was driven through two authorities that never agreed.** The
  ladder adopts the client's real player there, and drove it two ways at once: the per-tick legs
  through the client's own chain (really `LocalPlayer`), and 36 single-shot actuations —
  `holdItem`, `aimAtBlock`, `useItemInHand`, `useBlock` — through a `ServerPlayerAvatar` wrapped
  around the `ServerPlayer`. Vanilla lets the CLIENT own the selected slot (it travels up as
  `ServerboundSetCarriedItemPacket`) and the rotation (up every tick in `MovePlayerPacket.Rot`), so
  those writes landed on a copy nobody was steering.
- **It was measured before it was fixed, and the measurement refuted both standing hypotheses.**
  `wd.actuatorSplitOnAnAdoptedBody` recorded server slot 4 against client 0, and server aim
  (-55.32, 29.55) against client (283.23, 0.00) — **identical ten ticks later**. Not the race
  everyone assumed, and not a write that survived unopposed: the two sides simply held unrelated
  values. Measuring first was deliberate — rerouting and re-running the ladder could not have told
  a working fix from a fix that changed nothing, because both render as green.
- **The ladder could never have caught this.** `runJourneyServer` is headless: no client exists,
  `realPlayerHelm` is false, both halves are the server, and a server cannot disagree with itself.
  Rungs 1-13 are green on a topology where the defect is unreachable. A defect's absence from a
  suite is a fact about the suite's topology, not about the defect.
- **The fix is choosing the right avatar, not writing new actuators.** `ClientPlayerAvatar` already
  did all of it correctly, including the `ServerboundSetCarriedItemPacket` whose absence the ruler
  measured. `BotApi.clientAvatar()` hands it out from the client side — it cannot be constructed in
  `JourneyRig`, because merely NAMING a `net.minecraft.client` type from code that also runs
  headless forces the JVM to resolve it there (StageWright's `DriverFeed` records that exact
  `NoClassDefFoundError`, whose symptom is a server running a whole suite with no player). Built
  fresh per call: it binds `mc.player`, which dies on respawn and dimension change.
- **33 call sites moved, 10 stayed, 2 were split — "all 36" would have created defects.** The
  client's `breakHold` only presses a keybind, so routing `breakItWhereItStands` there turns every
  in-place dig into a silent no-op; `canBreak` is `default -> true` on the client, so routing it
  yields an always-true predicate, which is worse than deleting the check; `placeTally` is not on
  the interface at all. Two sites mixed aiming with breaking on one avatar and now use one of each.
- **A0's acceptance is a second scene, never an edit to the first.**
  `wd.actuatorSplitOnAnAdoptedBody` stays a ruler for the raw divergence, so a future revert
  re-reports it; `wd.actuatorSplitThroughTheClientAvatar` exercises the fixed path. Its assertions
  are on the CLIENT's own values, **not** on the two sides agreeing — agreement is a symmetric
  predicate, equally satisfied by both sides being wrong together. The aim half is asserted as well,
  against a requirement recomputed from geometry; rerouting eight `aimAtBlock` sites with nothing
  but `ctx.record` rows watching them would have left that half able to regress silently.
- **A constant target is an assumption wearing a guard's uniform.** Both criteria have the shape
  「the client ends up at the target value」, which passes with the actuator contributing nothing
  whenever the client already sat there. The first version hardcoded slot 4 and one aim cell, with a
  comment arguing 4 was safe *because it is not 0* — testimony on behalf of a guard that did not
  exist. Both targets are now derived from the pre-write reading: the slot as `(clientSlot + 4) % 9`,
  and the aim cell as the candidate maximising the SMALLER of its two angular gaps, so one axis
  cannot be carried by the other. Measured: the hardcoded cell sat 5.21° from the body's own facing
  against a 5.00° tolerance, leaving yaw no range and pitch doing both jobs; derived, it scores 34.41°.
- **A negative control instead of a ritual that would have rotted.** The aim criterion is only
  evidence if it can fail, and on a healthy tree it never does. Rather than hand-reverting a live
  call site once to watch it go red, the ruler evaluates the twin's *shared* predicate against the
  server path's own reading on every run and records whether it came out false — 有效 on both runs
  so far. It is `ctx.record`, not `ctx.check`: the ruler has no verdict by design, and asserting
  there would amount to requiring that the defect continue to exist.
- **A chooser must record its input, not just its choice.** The aim cell depends on the body's
  current facing, which varies between runs, so a prediction that misses is ambiguous between a
  broken chooser and a changed input. `aim.选格依据` prints both on one line, and earned it
  immediately: the validating run started at −43.82° rather than the −48.79° the prediction assumed,
  chose a different cell, and recomputing against the recorded input reproduced that cell exactly.
- **The proof is the reversed asymmetry, not the equal numbers.** Same run, same body: the ruler
  reads server 4 / client 0, the fixed path reads server 0 / client 4, converging to 4/4 within ten
  ticks. A false fix that wrote both sides would show 4/4 immediately, with no interval where the
  client leads — so that intermediate state is the packet going up, and it distinguishes two
  mechanisms that share a final state.
- **A green here means the mechanism is right, not that the threading is safe.** Both `thread.*`
  rows read `Server thread`: these calls write client state across a thread boundary, and such
  writes mostly do not throw, so this can be agreement that happens to hold. The coherent fix —
  originating single-shot actions from the client tick chain — is **not built**, and
  `BotApi.clientAvatar()` states it as an open defect rather than as a rule no caller obeys.
- **A test that stages one side and asks the other measures nothing.** The acceptance scene's first
  version put a stone into the SERVER's inventory and called the client's `holdItem`, which searches
  the CLIENT's — so it failed with a symptom identical to the real defect. Reading its numbers would
  have concluded the fix did not work and sent someone to repair working code. Its probe is now
  `setSelectedSlot`, which is independent of inventory contents and exercises the very packet under
  test.
- **Which build produced a reading is now readable, not recalled.** The invalid run records
  `slot.holdItem返回`; the corrected one records `slot.动作`. Changing an assertion's method changes
  its evidence key, so a stale-bytecode run identifies itself instead of looking like a verdict.
- **The gates run on the joined body.** All six now set `-Dworlddriver.realPlayerBodies=true`.
  Predicted and confirmed: NeoForge's `wd.serverAvatarEarnsAdvancement` disappeared, because
  `PlayerAdvancements.award` has an `instanceof FakePlayer` branch that earns nothing. One new red
  on both loaders, `wd.crystalBlastOnThePillar` — and it is the scene losing its isolation rather
  than a driver regression: `body.inLevelEntityIndex` flipped false->true, so `Explosion.explode`
  now finds the body and launches it 5 blocks sideways off obsidian that never broke. The scene
  asserts footing and is being failed by ballistics; its own class note predicted this in advance.

## 2026-08-20

- **The integrated ladder climbs on the client's real player.** 集成服上验证本就需要真实玩家来执行,
  and until now all three topologies spawned an invulnerable fake body beside the real player and
  drove that instead — so the whole validation set this comparison exists to reveal (`fallDistance`
  pinned at 0, `isInvulnerableTo` refusing every source, a death that is a no-op, an advancement
  never awarded) was missing on the client topologies too. `JourneyRig.spawnBody()` now ADOPTS the
  player that is already there on `runJourneyIntegratedServer`.
- **How the same rung code drives either body: the helm, not the verbs.** Rewriting 25 000 lines of
  rungs into `mc.bot.*` verb calls was never on. `BotProcess.tick(Minecraft,…)` already
  default-bridges to `tick(Avatar,…)` over a `ClientPlayerAvatar`, so ONE process object drives a
  client `LocalPlayer` and a headless `FakePlayer`. A rung still builds a `TowerProcess` and hands
  it to `rig.drive`; only who advances it changes.
- **Four sibling paths that each engaged the helm became one.** `drive`, `settle`, `mineBlock` and
  `mineCellOrGiveUp` each called `ServerAvatarManager.register` for themselves. Under the real-player
  helm that would step `ServerPlayerAvatar`'s manual physics on a client-controlled body, which the
  client contradicts with its own movement packet every tick and vanilla resolves by rubber-banding.
  Routing all four through `startLeg` makes it structurally unreachable rather than conventionally
  avoided — the shape this repo keeps paying for is an invariant with siblings that ignore it.
- **`mc.execute`, never `onClient`.** The scene body runs on the server thread of a server the client
  is ticking against, and both `DriverApi.awaitMs` and `BotUtil.onClient` block the caller until the
  client answers. Starts are fire-and-forget; completion is polled from the scene's own await
  predicate, which is the only code that already runs on every tick of a leg.
- **One leg reading, not two.** `BotApi.userTaskLeg()` publishes `{seq, busy, kind, error}` as a
  single immutable snapshot. Two independently-volatile fields do not compose into an atomic pair:
  a poll landing between them reads `busy=false` beside the PREVIOUS leg's ending, so 「这一腿刚跑完」
  and「上一腿早跑完、这一腿还没装上」render identically. `busy` goes false only on the client thread
  and only once the process object that was installed has really left the chain, so the window
  between enqueued and installed can never be read as finished.
- **A third evidence key, because the integrated topology changes two variables at once.**
  `journey.steer` (`serverTick/ServerAvatarManager` vs `clientUserTask/ClientPlayerAvatar`) is
  recorded beside `journey.topology` and `journey.body` on every exit path. Without it a divergence
  is explained equally well by「假人的 gap」or by「客户端链和服务端链本来就不同」, and two arms are
  only readable when they differ in ONE variable. The fourth arm that would separate them does not
  exist yet.
- **The joining topology keeps the fake body on purpose.** Its client bot is in the other PROCESS,
  and a `BotProcess` object cannot cross a socket. `journey.body` says so rather than leaving it to
  be inferred.
- **The ladder's searches are bounded by nodes, not by the wall clock.** `pathfinderMaxMs` was 4000;
  it is now `Long.MAX_VALUE/2` with an explicit `pathfinderMaxNodes = 100_000`, the shape 58 scene
  sites already use, for the reason `PathFinder`'s own comment gives: a millisecond cap makes the
  same search answer differently depending on how busy the box is. Measured before changing it —
  `STOP cause=` appears zero times across three ladder logs against 496 `search-begin` lines — so
  this bound never once fired and this is hardening, **not** a fix for anything observed, and
  emphatically not a claim that the ladder became reproducible.
- **Readings that stated what was once true now ask.** The out-of-world report hardcoded
  「这具身体 isInvulnerableTo 恒为 true，会一直掉下去」; an adopted real body dies instead, which is a
  different diagnosis and must not print as the same one.

- **The ladder runs under all three topologies now, and every rung says which one it climbed in.**
  `wd.journey*` had exactly one run configuration, and it was the one with no client in the JVM at
  all — so a defect that lives on the client side could not be observed by it, only inferred from
  its absence. That is how `ServerPlayerAvatar.commandUseItem(false)` shipped calling
  `stopUsingItem()` (clears a flag) where `releaseUsingItem()` routes to `ItemStack.releaseUsing`: a
  fully drawn bow「fired」and produced no arrow, silently, past 222 green scenes and four rounds of
  wrong fixes. `journeyIntegratedServer` (a real client hosts the world, one JVM) and
  `journeyDedicatedServerWithClient` + `journeyJoiningClient` (a real client joins over a socket, two
  JVMs, its own port 25701) are the same ladder in their own run directories — own directories
  because a playthrough leaves its trees felled and its shafts dug, so two of them sharing one would
  each measure the other.

  Smoke-tested to rung 2 on both: the integrated run reads
  `journey.topology = integratedServer（真玩家 1：…@minecraft:overworld；mc.bot.* 在本 JVM=true）`,
  the joining run reads `dedicatedServerWithClient（…；mc.bot.* 在本 JVM=false）`, and
  `JoinedPlayerBodies.placeNewPlayer` — which had never once run against an `IntegratedPlayerList` —
  puts `agent-body-1` in the player list on both.

- **What a topology varies, and what it does not.** All three climb on the body
  `JourneyRig.spawnBody()` builds; none of them drives the human client's player. So the fake-player
  gaps — `fallDistance` pinned at 0, `isInvulnerableTo` refusing every source, an advancement that is
  never awarded — are properties of the BODY and are present in all three, and a difference between
  two runs is never explained by them. Driving the real player is unbuilt work rather than a switch:
  this rig hands a `BotProcess` OBJECT to `ServerAvatarManager` while the client side takes verbs and
  polls `status()`; `breakItWhereItStands` is server-only by construction (`Avatar.breakHold` on a
  client sets a keybind and breaks nothing without a multi-tick `continueDestroy`); and `awaitMs` /
  `mc.wait.*` sleep the CALLING thread, which from a scene body is the server thread the client
  process is waiting on. `JourneyRig`'s class note carried a sentence claiming the ladder already ran
  over `mc.bot.*` on the integrated topology; it never has, and that sentence is now what is true.

- **`journey.topology` and `journey.body` on every rung row, including a BLOCKED skip.** A results
  row that does not name its run cannot be compared with the same row from another run, which is the
  only reason to have three. Both are **read off the running game** — `isDedicatedServer()`,
  `BotHooks.isAvailable()`, and the players who are not the driver's own `JoinedBody` — rather than
  echoed back from the `-D` that asked for them, because a launch that did not do what it promised is
  exactly the case these rows exist to catch (a companion client that dies in architectury's
  transformer leaves the server waiting for a player with no timeout, and its own log looks fine).
  `bodyIsInvulnerable()` stopped being a literal `return true` for the same reason: both bodies do
  override `isInvulnerableTo`, so the value is unchanged, but a hardcoded row cannot report the day
  somebody drops the override for a survival-fidelity run.

- **`-Dworlddriver.realPlayerBodies=true` stays on for the client topologies, and it is not a copied
  line.** `ServerLevel.players()` is per level and the human client never leaves the overworld: rungs
  14–15 ask the NETHER's list (`BaseSpawner.isNearPlayer`) and 19–20 ask the END's
  (`EndDragonFight.tick`). Dropping the flag on a run that has a real player would produce no blazes
  and no dragon, silently, in a run that looks better resourced than the headless one.

- **The port-pinning guard is a rule about the family now, not a list of names.**
  `runConfigs.configureEach` in `fabric/build.gradle` excluded the self-driving runs by enumerating
  them, and the enumeration silently stopped covering the ladder the moment it grew a second and
  third topology — the new names matched no clause, fell through, and would have had their ephemeral
  agent ports overwritten with the pinned 39800/39801 pair. On the two-process topology that is two
  JVMs binding one port; everywhere else it is a headless run fighting a live dev client. A guard
  whose failure mode is「a config added later is quietly not covered」cannot be a list.

- **The ladder's own provisioning caught up with the framework's.** It deleted `world/` and nothing
  else, which was survivable while there was one ladder and nobody diffed its output. It now also
  deletes `saves/` (a client keeps its world there, so the integrated topology would otherwise reuse
  a played one forever), the stale results and heartbeat files (a run that dies before writing a
  header leaves the previous run's complete, plausible file exactly where a reader expects this
  one), and seeds `options.txt` with `pauseOnLostFocus:false` — vanilla singleplayer pauses when the
  window loses focus, which on a desktop stops the integrated server dead and reports a step timeout
  on whatever leg was in flight when somebody alt-tabbed.

- **A flight that laid nothing because the body was standing on its own bottom step, and a rule that
  answered two findings with one sentence.** `JourneyRamp.lay` stopped when a pass laid nothing new,
  on the grounds that「walking changes nothing when the block that refused is the one the next stand
  rests on」. That is true of a REFUSED placement and false of a body standing in the cell, and the
  ladder run of 2026-08-20 printed both an hour apart:

  ```
  cell.9.ramp.step.2   = 3, 58, 20 垫不上（… 六邻没有能贴的实心面），身体 0, 58, 19
  cell.9.ramp.step.2#2 = 3, 58, 20 垫不上（… 六邻没有能贴的实心面），身体 2, 56, 20
  cast9.ramp.flight    = 3 级：2, 56, 20 → 3, 57, 20 → 2, 58, 20（… 身体 2, 56, 20）
  cast9.ramp.laid      = 0/3 级垫好了（身体 2, 56, 20）
  ```

  The first pair is the same cell refused from two stands nine blocks apart — `#2` is the rig's own
  duplicate-key marker — and the rule earning its keep. The second is `flight.get(0).below()` being
  the cell the body was standing in, where one cell sideways is the whole answer. `Stop` now names
  why a pass stopped and `stepAsideFor` spends one step-aside on `BODY_IN_THE_WAY` and none on the
  other three; the step-aside is handed back only by a pass that makes progress, so a body that
  cannot get off the flight asks twice and stops.

- **The loop can be driven by a scene now, which is why the fix is measured rather than argued.**
  The walk-and-place loop needed a `JourneyRig` for four services and only two of them were real:
  the other two are a level and a body, which any scene has, and the placement comes off the
  `Avatar` interface (`JourneyStairs.placeInto` grew an `Avatar` overload). `layWhereItStands` and
  `stepAsideFor` are therefore ordinary static functions, and `wd.rampStepsAsideWhenTheBodyIsInItsOwnStep`
  calls them over a staged alcove with a real body and real cobblestone. What is left in `lay` is
  the `walkTo` and the recursion, and those two lines are covered by reading the diff — the scene's
  own javadoc says so, and substitutes putting the body in the cell the decision named.

- **The planner is not where this belongs, enumerated against the run's own geometry.** Refusing to
  plan a course into the body's cell — the walkable twin of the sight-line reservation — has no
  second answer here. From the landing `2,59,20` the descent has three continuations and with
  `2,56,20` forbidden all three die: `2,58,19` needs `2,57,19`, the descent staircase's head room;
  `3,58,20` continues only into `2,57,20` (whose support IS the body's cell), `3,57,19` and
  `3,57,21`, both already cobblestone from earlier cells' flights; `2,58,21` continues into the same
  three. A reservation there would have moved the failure one leg earlier.

- **The pinned tower's drift into the flooded floor row is a consequence of the empty flight, not a
  second defect.** `wd.rampFootholdRisesWithTheFlightItLaid` measures `JourneyShaft.footholdInColumn`
  on the landing's column before and after the production loop runs: before, the column's only cell
  with anything solid under it is the alcove floor row, and that row is under water — which is what
  `climb.12.afloat = 2, 56, 18 浮在水里，8 次都没落地` reports and what a tower cannot start from.
  After the flight, the same call returns a dry cell three rows up, and it is a step of the flight.
  The column had one foothold because the staircase that would have given it three was never built.

- **`approach` says where it went.** Cast nine printed the body at `2, 56, 20` twice — once at
  `buildTo` entry and once in `.laid` — so `approach` either found nowhere to walk to or walked and
  did not arrive, and the run could not say which, because it wrote no row at all. `.stand` and
  `.standShort` are unconditional for that reason. No arena in this repo can see a failed walk, so
  if the WALK is the remaining blocker only the ladder will say so.

- **The fourth rung-12 family — a finished ring cell in the way of an uncast one — is neither an
  ordering problem nor the ring's shape, and the hundred「occupied stands」are the alcove's own
  rock.** Established rather than argued, because the fix each reading points at is a different fix.
  The run after the borrowed-step take-back landed shows cast 8 pouring (`cast8.clear3 = 1 格要清 …`,
  `cast8.result = CONSUME`) and cast 9 — `4,60,20`, the last cell — dying on the FIRED ray:

  ```
  cast9.picks.1 = 4, 60, 19 Block{minecraft:obsidian} face=up → 落进 4, 61, 19
                  （想浇 4, 60, 20，瞄 5, 60, 20，身体 3, 60, 19）
  ```

  The blocker is real and is this rung's own obsidian. The body, however, is at `z=19` and the target
  at `z=20`: the shot is a diagonal from the NEXT RANK, and a diagonal is the only shape that can
  reach a neighbour's cell at all.

- **Ordering cannot help, and one staging per cell proves it for all 3 628 800 orders.**
  `wd.pourLineRingOrderCannotShadowAPour` stages each ring cell with all nine others already
  obsidian — the worst shadow any order can produce, and every order casts a subset of those nine.
  All ten keep columns in their own rank, and all ten keep one at the row `standLevelWith` verifies
  (`target.y - 1`; the bottom pair's verified row is under the alcove floor and is excluded by the
  geometry, not by taste). The shadow is a property of the RANK *and the ROW*: the arm's row-by-row
  control reads `y221:1 y222:0 y223:0 y224:0 y225:1` for the neighbouring rank, with `y223` the row
  the raise asks for. Its first draft swept the rows together, and its own rig check caught that.

- **`落脚格被占=100` is the scan reaching outside the excavation.**
  `wd.pourLineOccupiedStandsAreOutsideTheAlcove` splits the vote against the carved volume:
  `101 = 100 outside + 1 inside`, and the one inside is a registered flight step. The arena
  reproduces the run's 100 exactly — the scan is four cells back and two either side of a mould
  pushed two out of a five-wide alcove, so most of it is the rock the alcove was cut into. Single
  digits of scaffolding cannot explain a hundred, so this is **not** another instance of「a
  recovery's placements become the next step's obstacle」 and the no-go list is the wrong place to
  extend. That arm's exact-sum criterion also caught the arena being one row too shallow: 20 outside
  candidates had no floor staged under them and were refused as `脚下不实心` instead — a
  「mostly rock」criterion would have passed over it.

- **No production change.** What is left is the DELIVERY, and the run names it:
  `cast9.ramp.laid = 0/3 级垫好了（身体 2, 56, 20）` — a flight that laid nothing because the body was
  standing on its own bottom support, whose refusal `JourneyRamp.lay` treats as「walking changes
  nothing」— then `cast9.raisedY = 59/59（停在 3,17，指定柱 2,20，不是同一柱）`. The column the ray
  chose was right; nothing got the body into it. That lives inside a walk-and-place loop that needs a
  `JourneyRig` to drive, so it is written down rather than fixed blind.


- **The step-advance log is budgeted per LEG now, not per walker, and it says whether it is
  complete.** A walker outlives a whole rung, so the old 8-line budget was spent in the opening
  seconds of the 5 209-tick crossing and the four wedged hops — 900 ticks each, 61 to 76 walk edges
  apiece — produced not one advance line between them. That is exactly the log that would say
  whether the pointer advanced through nodes the body never walked. Sixty-four per leg: it covers
  the 61 edges the worst measured hop walked, and 24 hops x 64 x ~300 chars is about 460 KB for a
  whole crossing — still a log a human opens, which an uncapped one over a wedged hop would not be.

  <p>The leg boundary is `JourneyFlight`'s constructor, which is where the `guardForcedRepaths`
  delta is already taken and the only place a `JourneyFlight` is built. One definition of「leg」,
  not a second one. `Walker.legEpoch` gates a log budget and nothing else — no decision the bot
  makes can observe it — which is what makes a static safe here where `lastTickTrace`'s note says
  one would not be.

  <p>And the leg reports its own budget usage (`步进记了 N 条（记满了…／…是完整的）`), because a
  reader cannot otherwise tell「this hop advanced 61 times and all 61 are here」from「this hop
  advanced 300 times and you have the first 64」. Same lesson the landing allowance taught from the
  other side one commit ago.

- **A leg now measures whether its plan points BACKWARDS**, per grounded tick: the node's distance
  to the leg's goal minus the body's own (`计划最往回指`). The forced-repath line names the discarded
  plan's last node, but only when a discard happens — and「the plans themselves route backwards」is
  precisely the branch where none does. This needs no plan end node and no plumbing: it is computed
  from the goal the leg already holds. A leg that walks 61 edges to a net −8 either shows a positive
  worst here or it does not, and that is the whole question.

- **Written down beside the guard: it is also an unplanned bridge-builder.** 491 fires, 142 blocks
  plugged, a 127-block dirt causeway across a lava sea that no plan asked for, and a crossing that
  then shuttled along the bridge it had made. Recorded in `strideFloorGuard`'s own javadoc and
  **deliberately not acted on** — the backfill may be load-bearing, since without it the body may
  have no route across a lava sea at all, and that is a decision the next run's readings should make,
  not a paragraph.

- **Rung 14's shuttle: the box is a lava sea, and the only ground in it is the causeway the body
  built itself.** Read out of that run's own region files rather than inferred — 18 458 lava cells
  against 2 543 netherrack in `x∈[62,100] z∈[76,112] y∈[20,50]`, and **127 dirt cells** running
  diagonally from `(74,86)` to `(96,110)`. Dirt does not generate in nether wastes: that causeway is
  the body's. It was not planned either — the plans walked one `bridgePlace` edge per hop, while the
  **stride floor-guard fired 491 times in that crossing and plugged 142 blocks**, on 184 distinct
  cells of which only 5 ever reached the 12-fire plug dwell. The crossing bridged 30 blocks of lava
  sea one safety backfill at a time, reached the tip, and walked back down its own bridge.

- **Which of the two possible causes it is cannot be decided from that run, and saying so is the
  finding.** A leg that walks 61–76 edges to a net −8..−28 has either been given plans that route
  backwards or has had good plans taken away from it under its feet. Both fit every row: `goto.N =
  end=null err=null` on all four wedged hops (no error, no verdict, the hop's tick budget cut it),
  `离计划最远 9.12/10.34 格` while grounded with `move=walk` (a one-cell edge whose node is ten cells
  away), and search counts that do not separate them (hop 9 wedged on 42 searches, hop 2 was healthy
  on 34). The waypoint-standability theory died on the same table: hop 2 aimed at a column with
  7/169 standable neighbours and walked 42/48, hop 6 aimed at 134/169 and wedged.

- **The reason it cannot be decided is that the walker's plan-discard was silent.** `guardPinStreak
  >= 30 → path = null` logged nothing and was counted nowhere, and with `GUARD_PIN_HOLD = 8` a fire
  every eight ticks keeps that streak alive — the crossing had fires in 132 of its 187 seconds, up
  to 19 in one second. So it now says so, once per event, naming the streak length, the plan it is
  throwing away (remaining nodes and last node), the body, and **how many DISTINCT stride cells the
  streak covered**. That last number is the one that separates the two situations the counter was
  built for: a livelock is one cell (the run that motivated it was 567 pins at one cell), a rim walk
  is many. `JourneyFlight` reports the per-leg delta beside the moves the leg walked.

  <p>**Nothing branches on it.** Whether the streak should reset when the cell moves is a behaviour
  question this run cannot answer — `wd.serverKeepsWalkingAtALavaRim` already records that the
  escape hatch is reached in one approach shape and not the other. Instrument first.

- **`wd.guardRepathSeparatesARimWalkFromALivelock`** stages one lava trench and drives it twice, the
  only variable being whether the body is allowed to travel along the shore. Both arms must force a
  repath — that is the control, and an arm where either does not fails as THE RIG — and the line must
  then report 1 cell for the body held against one lip and more for the body walking the rim.
  Sabotage-verified: with the distinct-cell count pinned to 1 the arm fails. The first cut aimed the
  travelling arm at 150°, which is mostly −z; the body walked backwards off the arena, pinned on one
  cell, and the rim reported the livelock's own answer.

- **The landing allowance did not cost the next ladder run anything: it never ran.** The run after it
  went 286 blocks short against the previous run's 141, and the allowance is not why.
  `settleToGround` writes a `fortress.landing.<hop>` row unconditionally on entry, before its settle,
  and **the results file contains none** — zero in nine hops, so every hop ended grounded, the
  predicate declined and no `HoldStill` ever ran. Zero ticks spent, zero trajectories changed. The
  row was added for exactly this: a wait that costs nothing is indistinguishable from a wait that
  never happened unless it says which.

- **The two runs cannot be compared, and the divergence is visible at hop 2.** Same seed, same
  arrival cell, hop 1 ending at the same place — then, at the same lip, one tick apart:

  | | run A (no allowance) | run B (allowance) |
  |---|---|---|
  | `ground.2.0` | `t=105 (50.325, 53.0000, 50.994) 速度 (0.091, -0.078, 0.065)` | `t=106 (50.321, 53.0000, 51.005) 速度 (0.093, -0.078, 0.062)` |
  | plan in hand | `50,47,50[fall3]（第 2/19 步）` | `49,53,50[walk]（第 1/21 步）` |
  | fall | 8 blocks | 3, then 7 |

  Four thousandths of a block apart, and a **different plan** — 19 steps against 21. `PathFinder`
  budgets itself in wall-clock (`advance(sliceMs)` stops on `System.nanoTime()`, the search on
  `spentMs > maxMs`), so how many nodes A\* expands per tick is a property of the machine that hour.
  A fixed seed does not make this rung reproducible, and n=1 per version cannot rank two versions of
  anything downstream of a plan.

- **The new run died of something else entirely: a shuttle, not a stop.** Terminating condition
  moved from `hazardBlockingARetry` to `MAX_WEDGED_HOPS` (`连着 4 段没比纪录（263 格）更近`). Hops 1–3
  were the healthiest this crossing has walked — 128 blocks in 1 230 ticks, **9.6 tick/block against
  the previous run's 11.4** — and then hops 4, 6, 8 and 9 each burned their full 900 ticks inside one
  box (x∈[69,96], z∈[77,108], y≈43), walking 61–76 edges while 9–10 blocks off the node they were
  steered at, cornering on block edges for up to 23% of their grounded ticks. The 37.7 tick/block
  headline is that shuttle, not the walking.

- **`surroundings` — the row every wedged hop prints — could not tell a body standing from a body
  falling.** It asks the cell under the body's CENTRE, and a player is 0.6 wide:

  ```
  fortress.around.8 = 脚下=air …… onGround=true 落速=-0.08 血=20 脚下到实心=>16
  ```

  That reads as a body over a void. It was a body standing, cornered on a neighbour with its own
  column open sixteen down — recoverable only from a different row of a different hop (hop 9's
  flight, `y 43→43`). A body one tick past a lip prints the same three readings for the opposite
  reason, because `onGround` is a tick stale there. `wd.crossingRowSeparatesAPerchFromMidAir` stages
  both over one shaft and measured the rows **byte-identical** while their soles read `0.1500/0.36`
  and `0.0000/0.36`. The row now carries the sole and its per-cell row, through
  `WalkerGeometry.soleOnSolid`/`soleRow` — the repo's single enumeration, so this row and the guards
  that steer on it cannot disagree about what standing means. `JourneyFlight` learned this for the
  recorder already; the crossing's own snapshot never got it.

  <p>The arena's own shaft is 18 blocks deep and that is squeezed, not chosen: deeper than the row's
  16-cell probe so both bodies read `>16`, shallower than what the walker refuses. The first cut made
  it bottomless, `strideFloorGuard` correctly killed the momentum and sneak-pinned the body on a
  0.0001-wide sliver of the lip for all 160 ticks, and the arm reported a rig failure over a guard
  doing its job.

- **Rung 14's Nether crossing stopped 141 blocks short because a hop's verdict was taken from a body
  one tick above the floor.** The crossing itself is healthy — 11.4 tick/block, 1% of its ticks
  without a plan, 2 968 of a 21 600-tick hop budget spent — and it quit on this:

  ```
  fortress.crossing = 6 段，还差 141 格 …… 第 6 段之后停手：身体还在下坠
                      （179, 43, 198，落速 -0.38 格/tick，脚下到实心 0 格）
  ```

  `脚下到实心 0`: the body was a hair above netherrack, mid-landing, and would have been standing on
  it on the next tick. `hazardBlockingARetry` is right that a walk order cannot act on a falling body
  — its own note says "its position is not where the next plan will start from" — but the reading was
  taken from a body it never let finish falling. Eighteen of twenty-four hops and 16 200 hop ticks
  went unspent over one tick of patience, against 141 blocks the same leg's own pace prices at
  ~1 600 ticks. `oneHop` now wraps its whole continuation in `settleToGround`: when the leg ends with
  the body still falling it gets `LANDING_TICKS = 26` under `HoldStill` first, and only then is the
  hop judged, its distance measured and the next hop started — all four want the same settled body.
  Twenty-six ticks is arithmetic, not caution: vanilla gravity covers 23.4 blocks in 26 ticks and
  `survivableFall(20) = 22` is the deepest dry drop this body walks away from, so anything past the
  allowance is a genuine chasm and the verdict is right to stop on it. A hop that ends on the ground
  pays nothing — the predicate is asked first and the settle skipped.

- **The walker is NOT the defect here, and the measurement says so twice.** Both falls on that leg
  launched from a tick whose sole read `0.0000/0.36` while `onGround` still read true — vanilla's own
  ground sweep disagreed with the flag on the same tick, so it was genuinely a tick stale. But
  nothing in the walker steers on it: `footingGuard` and `strideFloorGuard` both open on
  `WalkerGeometry.soleOnSolid`, which was 0 on that tick, and both were silent for the reason the
  evidence row spells out — **the drops were 4 and 8 blocks** against a lethal line of 22, and the
  row's own re-derivation of the lethal-edge brake off the level ends `→ 不该响`. Teaching either
  guard to refuse those strides is the failure `wd.serverWalksOffASurvivableLedge` was committed to
  catch: a guard that pins at every lip turns a Nether crossing, which is nothing but lips, into a
  wall. The new arena reproduces the launch tick verbatim (`脚底实心 0.0000/0.36，onGround=true，
  vanilla 自己那一问=没有`) and records `守卫钉住 0 tick` beside it.

- **Two arms, one variable: how far it is to the floor.** `wd.crossingWaitsOutASurvivableDrop` walks
  a body off a four-block lip with the guards at their live values and judges the same fall twice —
  once at the instant the leg would have ended (`脚下到实心 0 格，还在下坠`, the control, which fails
  the arm as THE RIG if it comes back clean) and once after the allowance, where it must clear.
  `wd.crossingStillStopsForALongFall` drops the same body thirty-nine blocks: the allowance expires
  with it ten blocks up and the verdict must STILL stop the crossing, so「the verdict now clears」
  cannot be satisfied by deleting the branch. Sabotage-verified: with `LANDING_TICKS = 0` — the
  pre-fix crossing exactly — the first arm fails all three checks and the second stays green.

- **Rung 13 failed again on the next ladder run, faster and one cell east, and neither half of the
  first fix was wrong — both were too narrow.** The 15:20 run lit the portal, surveyed the doorway
  and derived `站 3,57,20 迈进 4,57,20（门洞第 0 排），现在就能走进去`, then spent all three legs
  standing on `3,58,20`, one row directly above that doorstep:

  ```
  portal.walk.1 = XZ 目标 3,57,20：2,58,20 → 3,58,20（挪了 1 格，13 tick） end=arrived
  portal.walk.2 = 3D 目标 3,57,20：3,58,20 → 3,58,20（挪了 0 格，11 tick） end=path-consumed
  portal.walk.3 = XZ 目标 3,57,20：3,58,20 → 3,58,20（挪了 0 格，11 tick） end=arrived
  ```

  **The doorstep was not stale.** Read out of `run-journey`'s own region file rather than inferred:
  `3,57,20` is air over cobblestone at `3,56,20` — standable, correctly derived — and `3,58,20` is
  air whose own floor is air. The body was legitimately perched on the north lip of the cobblestone
  at `3,57,21`, `脚底实心 0.0563`. Nothing invisible was left behind by rung 12.

- **`Goal.XZ` ignores Y by construction, so half the retry legs asked a question the body had already
  answered.** `XZ(3,20,0).reached(3,58,20)` is TRUE — the column matches and the row is not part of
  the question — which is why `walk.1` and `walk.3` both report `arrived` with the body never on the
  doorstep. `walk.3` is a pure no-op that reports success, and because it moved zero cells it also
  fed the two-still-legs terminator that ended the rung. **A descent to a specific cell has to be a
  3D goal**, so `JourneyPortalEntry.legGoal` now converts the flat turn to `Goal.Block` when the body
  is already standing in the target column, and keeps `Goal.XZ` for a body outside it. The
  alternation is still an alternation — the point of it is to change the question on a retry, and
  deleting it would have passed every clause that only checks the descent.

- **`step == 1` was a POINTER index standing in for「the body has not walked this plan」, and the next
  run walked straight through it.** A\* answered `Goal.Block(3,57,20)` from `3,58,20` with a TWO-node
  plan — `[3,58,21 → 3,57,20]`, sideways onto the standable cell beside the body and then down — and
  the walker spent both nodes in ONE tick, at the same body coordinates to three decimals:

  ```
  步进 序=1 因=passed 旧步=1 新步=2 w=3,58,21 nx=3,57,20 身体=(3.700,58.000,20.794) 脚底实心=0.0563
  步进 序=2 因=within 旧步=2 新步=3 w=3,57,20 nx=无(末节点) 身体=(3.700,58.000,20.794) |w.y-p.y|=1.000
  ```

  `旧步=2` on the deciding line, so no index test can see it. The reading is the **row**: the body has
  not gone down under this plan while its foot row is still at or above the row the plan was searched
  from, and walking a plan's flat nodes is not taking its descent. An intermediate cut,
  `foot.equals(path.get(0))`, released one step too early for exactly that reason and is recorded in
  the guard's javadoc next to the arena trace that killed it.

- **Widening the scope re-opened a deadlock this guard already documents, and the cost was measured
  before anything was tuned.** A temporary per-firing probe over a filtered gate run counted every
  final-node hold and printed the stall clock and the sole at each:

  | | holds | stall clock | sole | body |
  |---|---|---|---|---|
  | `wd.serverMineHarvest` | 100 | 0 → 27, a smooth ramp | 0.0015..0.0041 | `y=222.000` at **all 100** |
  | the two descent arenas | 9 | **0 on 8 of 9**, max 13 | 0.0180..0.2631 | shuffling onto the lip, then dropping |

  Identical shape, opposite meaning. mineHarvest is the stride-floor-guard deadlock already in the
  javadoc — the guard refuses the very stride the hold insists on — re-entered once per plan and now
  sat in for 27 ticks instead of released; the scene went from 113 ticks and green to 191 and
  `broke 2/4`. The arenas are the opposite: the stall clock RESETS on eight of nine holds, meaning the
  body got closer to the node on that very tick. So the final-node branch does not reuse
  `TAIL_HOLD_STALL_TICKS` (30) but requires `noStepProgressTicks == 0` — **a hold may extend an
  approach that is working, and may not outlive one that has stopped.** Over the same run that keeps
  8 of 9 arena holds and 12 of 100 mineHarvest ones. `wd.serverMineHarvest` is the standing witness:
  64 archived gate runs at 104..125 ticks, and every widening without this term put it at 190+.

- **`wd.serverStepsDownAPlanItSpentInOneTick`, `withRequired(false)`.** A second verbatim 6×7×7 copy
  of the same rung's doorway, from the 15:20 run, at the ladder's exact stance (`+0.700, +0.794`,
  `脚底实心 0.0564`) — the geometry that produces the TWO-node plan, which the 13:44 copy does not.
  Both arms differ only in `walkerDescentNodeHold`:

  | arm | ticks | moved | minY | holds | on the doorstep |
  |---|---|---|---|---|---|
  | control (hold OFF) | 60 | **0.00 格** | 211.00 — never descended | 0 | no |
  | subject (hold ON) | 19 | 0.30 格 | 210.92 | 3 | **yes** |

  Under `step == 1` the subject arm read `60 tick，走了 0.00 格` and went red — that run is the proof
  the criterion can fail. Both descent arenas also now record the plan tick by tick and the last
  search's `PathStats`, because these arenas emit no `[walker]` line into the run's log at all, so
  「the plan was two nodes and both were spent at once」had to be recorded in the scene or it was not
  recorded anywhere.

- **`wd.portalEntryWontAskForAColumnItStandsIn`, `withRequired(false)`.** The smallest world in which
  the `Goal.XZ` blindness is real: a doorstep, a perch, and a body on the perch's lip one row above.
  The control drives the goal the old alternation issued and requires it NOT to arrive — measured,
  `XZ 目标 …,241,…：…,242,… → …,242,…（1 tick） end=arrived`, a success reported from one row up
  without moving. The subject requires `legGoal` to hand back a `Goal.Block` and that goal to land the
  body on the doorstep (45 ticks), and a fourth clause requires a flat turn from OUTSIDE the column to
  still be `Goal.XZ`, so the fix cannot be a deletion wearing a fix's clothes. The stance is checked
  too: at the cell centre the body has no support and falls onto the doorstep by gravity, which would
  pass every clause while measuring nothing.

- **Rung 13 could not walk the last cell into the portal it had lit, because the walker spends the
  LAST node of a plan that steps down.** The run of 2026-08-20 13:44 got the geometry right — it
  surveyed the doorway, declined the one open row a 1.8-tall body does not fit through, priced the
  ways in, took the one that cost a single cobblestone (`3,59,19`), mined it and re-derived the
  doorstep from the world afterwards. Then it could not move:

  ```
  portal.walk.2 = 3D 目标 3,58,19：3,59,18 → 3,59,18（挪了 0 格，花了 11 tick） end=path-consumed
  portal.walk.3 = XZ 目标 3,58,19：3,59,18 → 3,59,18（挪了 0 格，花了 11 tick） end=path-consumed
  ```

  The walker printed the cause on the tick it happened, both times:

  ```
  步进 序=1/8 因=within 旧步=1 新步=2 w=3,58,19 nx=无(末节点) 身体=(3.463,59.000,18.939)
       cur2=0.316 |w.y-p.y|=1.000 onGround=true 脚底实心=0.2168
  ```

  A* answered `Goal.Block(3,58,19)` with the one-step plan `[3,59,18 → 3,58,19]` — its only node both
  first and last. `within` (`cur2 < REACH_DIST_SQ = 0.45 && |dyNode| < 1.2`, no ground test) accepted
  it while the body stood a full block above it, the pointer reached `path.size()`, and the segment
  ended `path-consumed` **on the tick the plan was adopted**, goal unreached, body where it started.
  Re-asking gets the identical plan, which is what the rung's two-still-legs terminator said out loud
  — that part is the design working: the message named the mechanism instead of blaming the transfer
  timer, and pointed at `portal.walk.*` rather than at `Entity.handlePortal`.

  This is the same defect `wd.serverStepsDownAPerchItPlanned` closed three commits earlier, at the one
  node that fix scoped out. `WalkerTickProgress#unwalkedDescentConsume` now covers the final node too,
  under two clauses that were bought rather than argued (each was removed and the gate re-run):
  `goal.reached(w) && !goal.reached(foot)` — spending it would report an arrival that has not
  happened — and `step == 1`, the one-step plan, where zero movement is guaranteed by construction.

- **Both scoping clauses cost a scene when they were left out, and the gate said which.** Holding
  every final node below the feet wedged `wd.serverFightsAFlyingBlaze` into a **60-second server
  tick** and a watchdog crash (`java.lang.Error: Watchdog` in `PathFinder$Search.advance`): that
  scene pumps ~3 000 walker ticks inside ONE server tick, so a held node that never resolves is a
  pathfinder search per iteration. Adding only the arrival clause cleared the blaze but left
  `wd.serverMineHarvest` red at `broke 2/4` across two runs — with a probe showing the hold never
  fired inside that scene at all and **299 times inside `wd.descent`**, whose knock-on is what moved
  the sweep. `step == 1` cuts the blast radius to the shape the ladder reports and both go green
  (`wd.serverMineHarvest` 115 ticks). A longer plan whose tail is a step down still ends one cell
  short — but the caller re-plans from where it stopped, and that re-plan IS a one-step plan.

- **It is rung 12's geometry and the walker's defect, and neither half is optional.** Rung 13 passed
  on the two ladder runs before this one (06:41, 10:26) and never walked on either: that casting had
  left the middle row's front open (`3,58,19 = air 站得住`) and the body was already standing ON the
  doorstep when the rung started, so `stepFrom` answered immediately — those runs' evidence has no
  `portal.walk.*` row at all. The 13:44 pour left five more cobblestone cells in the alcove
  (`3,57,19 3,57,20 3,58,20 3,59,19 3,59,20`) and the body two cells west of them, so the walk to a
  doorstep one row DOWN ran for the first time. Rung 12's change is what exposed it; the defect is
  the walker's and older than both.

- **`wd.serverStepsDownTheLastNodeOfItsPlan`, `withRequired(false)`.** A verbatim 6×7×7 copy of that
  doorway read out of the run's own region file — the mined cell already air, the portal lit and
  checked (six `nether_portal` cells still standing, so the arm cannot walk a body up to six cells of
  air), the body at the ladder's exact stance. `+0.463, +0.939` off the cell corner is load-bearing:
  it is what makes `cur2` exactly `0.316`, and the cell centre makes it `1.0`, where `within` never
  fires and the arena reproduces nothing. Driven twice with `walkerDescentNodeHold` as the only
  difference:

  | arm | ticks | moved | minY | end | holds | on the doorstep |
  |---|---|---|---|---|---|---|
  | control (hold OFF = the ladder's build) | 60 | **0.00 格** | 212.00 — never descended | `path-consumed` | 0 | no |
  | subject (hold ON) | 4 | 0.76 格 | 211.92 | — | 3 | **yes** |

  The control's row is the ladder's two legs byte for byte. The rig hard-fails if the control walks
  in, if the two arms' hold counts are not `0 → >0`, or if the stance drifts off `脚底实心 0.2168`;
  the subject is judged on **standing on the doorstep cell**, not on having moved, and a third check
  requires the control to have ended for the ladder's own reason (`end=path-consumed`) rather than a
  timeout. Pre-fix, both arms read `走了 0.00 格 … end=path-consumed` and checks A and B were red —
  that run is the proof the criterion can fail.

- **Rung 12's third failure family: the scoop's own staircase stood in the cell the cast had to stand
  in — and in the line it had to shoot down.** Cast 8 of an `east` mould based at `4,56,19` pours
  `4,60,19`, and its two halves want the same cell for opposite things. `standBehind` laid a step at
  `3,58,19` *for that cell*, so the body could stand in `3,59,19` and shoot the backing along the
  axis. The WET half of the same cast fills the notch one row higher (`4,61,19`), so its flight had to
  reach `3,60,19` — and the only support for that landing is `3,59,19`. The lava half came back to
  find its own stand solid:

  ```
  cell.8.step       = 3, 58, 19 垫一格给 4, 60, 19 用 → 站得住了（cobblestone）
  wet.8.ramp.flight = 4 级：2, 56, 17 → 3, 57, 17 → 3, 58, 18 → 3, 59, 19
  cast8.picks.1     = 3, 59, 19 cobblestone face=west → 落进 2, 59, 19（想浇 4, 60, 19）
  cast8.clear3      = 浇线上没有可清的方块（3, 59, 19=cobblestone(壁龛内) …）
  ```

  The last row is the one that made it unrecoverable: `clearPourLine` exempted the blocker because
  `JourneyRamp.isStep` said the rung had placed it deliberately. **The rung built the obstacle and
  then excused it.** Membership of the step set is now an exemption from a SWEEP rather than a title
  deed — `JourneySight.blockersOnTheLine` hands a step back to the pour whose line it is standing in,
  unless the body is resting on it (all four corners of the footprint, not `blockPosition()`).

- **The reservation is redeemed, not enforced, and that is arithmetic rather than taste.** The
  sight-line no-go list (`JourneySight.onALineToCome` — cells a ring cell not yet cast has to shoot
  through) is the counterpart of `JourneyStairs.needsOpen`, which keeps cells WALKABLE. `JourneyRamp`
  now plans in two passes and prefers a route that keeps those lines clear, and says
  `ramp.borrowed.N` when it has to take one anyway. It always has to here: the wet cell is one row
  above the frame cell, so its landing's support *is* the frame cell's stand. Forbidding the fill
  would move the failure one leg earlier — measured in `wd.pourLineHasNoOtherWayUp`, where the strict
  pass finds no flight and the permissive one finds exactly one, resting on the reserved cell.

- **`4, 60, 18` in `cast8.stand.1` was never the stand's own reading.** The veto map is a HISTOGRAM
  over all 140 candidate feet, so a row prints one stand beside the reasons *every* candidate was
  refused for. `4,60,18` is the mould's uncarved frame corner and only feet at `z=18` name it —
  measured per candidate in `wd.pourLineBlockedByTheStepTheScoopLeft`, which walks
  `JourneyPour.standCandidates` and attributes each vote to the cell that cast it. The stand's own
  veto names `3,59,19`, at the target's z.

- **Three scenes, one staged alcove, `withRequired(false)`.** `wd.pourLineBlockedByTheStepTheScoopLeft`
  (the blocked shot and the vote attribution), `wd.pourLineTakesBackTheStepItBorrowed` (the take-back,
  with a body standing on the step as the control that must be refused), `wd.pourLineHasNoOtherWayUp`
  (why a refusal is not the fix). All three are staged at the instant the lava half walks back in —
  eight ring cells cast, the flight standing — because two of this family's three instances were
  caused by the PREVIOUS leg, and a single-leg fixture is blind to them. `JourneySight` and the pour's
  stand-choosing layer take a `ServerPlayer` instead of a `JourneyRig` so an arena can ask the
  production question at all.

- **The Nether crossing's 41% no-plan burn was a step pointer spending a descent the body never made.**
  Rung 14 (2026-08-19) reported `全程无计划 3551 tick` = 41% of the run, and that reading is not spread
  over the crossing: **3,517 of those 3,551 ticks are four consecutive hops standing at one
  coordinate**, `159,53,187`, and the other nine hops contribute 34 ticks between them (0.2–2.8%
  each). The hops the summary blamed were not it — hop 4 burned its whole 900-tick budget to travel
  4 blocks while holding a plan on **898 of them**. Two different failures, and only the second is a
  planning failure.

  At that coordinate the body had finished a dug descent perched on a cell whose OWN floor is air,
  held up by `0.125` of `0.36` of sole on the corner of `158,52,187`, with the column at `158,·,188`
  ending in the lava lake at `y=50`. `Walker.footingGuard` sneak-pinned it, correctly. A* answered
  with the three-node way out — `[158,53,187 → 159,52,187 → 159,51,188]`, every cell standable — and
  the walker spent all three in ONE tick without moving a block:

  ```
  步进 序=2/8 因=within 旧步=2 新步=3 w=159,52,187 nx=159,51,188 身体=(159.092,53.000,187.700)
       cur2=0.207 |w.y-p.y|=1.000 onGround=true 脚底实心=0.1250
  ```

  `|w.y-p.y|=1.000` is printed on the line that advanced. `within`'s vertical clause is
  `|dyNode| < 1.2`, so a waypoint a full block under the feet reads as reached — the sibling
  `WalkerTickProgress#airborneClimbConsume`'s javadoc named as still suspect when it fixed the
  climbing direction and left this one. The pointer descended; the body did not; the plan was gone.
  `WalkerTickProgress#unwalkedDescentConsume` now refuses that consume at the same single advance
  outlet, so the two directions cannot drift apart.

  **Holding the pointer is also what releases the pin.** `footingGuard`'s planned-descent exemption
  asks `path.get(step).getY() < foot.getY()`, which is true exactly while the node is held and false
  the moment it is spent — so the old behaviour re-engaged the sneak over the very step the route
  meant to take. The two mechanisms only compose in this order.

- **Everything after that was downstream, and it is written down because none of it fired.** With the
  plan spent, `path == null` makes every tick a safety repath: `latest.log` carries ~3,500
  `[pathfinder] search-begin` lines from the same start, twenty a second, for 2,700 ticks. Both
  governors that exist for this are switched off by the same condition. `walkerFutileSearchCap` (5)
  is exempted whenever `world.hasStuckPenalties()`; a body that is not moving trips the anti-churn
  every `CHURN_WINDOW = 400` ticks and each firing re-charges penalties that live `15 s × strength`
  — so **the「wait for the penalties to decay」hold is waiting for something the waiting prevents**.
  That leaves `NO_PATH_WAIT_CAP`, which is `900`, **exactly the crossing's own per-hop tick budget**,
  so it cannot be reached inside a hop. All four hops ended `end=null err=null`: no arrival, no
  error, no signal the crossing could act on. Left as it is deliberately — changing a number without
  a measurement that names what it should become is how numbers here have been mis-tuned before —
  but the coincidence is now on the record and in `wd.serverStepsDownAPerchItPlanned`'s `ledger` row.

- **Two arms, and the arena is a copy rather than a drawing.** `wd.serverStepsDownAPerchItPlanned`
  stages a verbatim 17×17×13 block copy of that pocket read out of the run's own region file, with
  the body at the ladder's exact stance (`+0.092, +0.700` off the cell corner — that offset IS the
  0.125 sole), and drives it twice with `walkerDescentNodeHold` as the only difference:

  | arm | 无计划 | moved | minY | holds |
  |---|---|---|---|---|
  | control (hold OFF) | **260/260 tick** | 0.89 格 | 213 — never descended | 0 |
  | subject (hold ON) | 10/260 tick | 7.98 格 | 211 | 20 |

  The control's 100% and the subject's 3.8% bracket the ladder's own two populations — its wedged
  hops ran 94–100% and its healthy ones 0.2–2.8%. The rig hard-fails if the control walks out.
  `wd.serverStillWalksDownAStaircase` is the other half: the same switch over an ordinary descent,
  where both arms must agree (35 ticks, 8/8 treads, both) AND the hold must be shown to have run
  (4 holds) — without that last clause two identical tick counts read the same whether the hold is
  cheap or dead code.

  **Two tidyings of the copy each destroyed the defect before it was believed.** Re-placing the 32
  FLOWING lava cells as sources built a 34-source lake the ladder never had and drowned the subject
  at tick 44; copying only the two genuine sources instead left a lava-free pocket that the CONTROL
  walked 19.61 blocks out of with a plan on 252/260 ticks. The lava is the wall that makes the pocket
  a pocket. The one deliberate deviation is that the box's faces are sealed, because the ladder's
  cave carries on past where the copy ends and this suite shares one world.

- **The hold is scoped by two terms and each was bought with a red gate.** Unbounded, it deadlocked
  against a guard refusing the same step: `wd.serverMineHarvest` went red at `100011,222,100000` with
  the stride floor-guard printing `bottomless stride … plug FAILED` while the hold insisted on that
  stride, and the sweep ended `broke 2/4`. Applied to a path's LAST node, it replaced the walker's
  arrival handling with a drive at a node the body was already at and carried it one cell past onto
  bottomless ground — same scene, same `2/4`. So: mid-path nodes only (`nx != null`, the scoping
  `airborneClimbConsume` already had), released after `TAIL_HOLD_STALL_TICKS` of stalled step
  progress (the file's existing stall window rather than a new number; the two arenas need ~1 tick
  per tread and 20 ticks per 260-tick drive).


- **A pin is not a reason to skip the flight check once a drift has already changed the column.**
  `JourneyShaft`'s drift correction used to read `climbPinned ? back : towerColumnClearOfTheFlight(...)`,
  so a pinned climb skipped the check in the one place the column is not the caller's any more. Rung 12
  of 2026-08-19 died on exactly that: the raise was pinned to column `2,19` (itself a stair column,
  which `climbFrom` records and deliberately leaves alone because it came out of the pour's ray),
  course one drifted to `1,58,19`, `driftWedged`, and `driftKeptPinned` adopted column `1,19` — a
  DIFFERENT stair column that nothing ever put through the chooser. Two dirt went into `1,58,19` and
  `1,59,19`, the body finished standing on the second, and all three ascent legs died. The new
  `towerColumnAfterDrift` runs the check whenever the drift moved the column, pinned or not, and keeps
  the record-only behaviour for a pin the drift has not touched — the two are one boolean apart, which
  is what lets a scene use the old one as the control for the new one.

  The tread audit was NOT the place for this and the same run proves it: `lava9.up` ran on that trip
  and mended the two treads it could see. The one it could not is the block under the body's own feet
  — a body cannot mine what it is standing on — and that audit runs once and never re-asks.

- **Two more arms on the staged flight, both `withRequired(false)`**, so the drift-adopt decision has
  coverage that costs a fraction of a second instead of a forty-minute ladder run.
  `wd.unwedgePinnedDriftRefusesTheStaircase` (stairwell cut through rock: the answer must stay null
  even for a pinned climb) and `wd.unwedgePinnedDriftTowersBesideTheStaircase` (a ledge exists, so it
  must be found and built on — without it,「the pin now refuses」would be satisfied by a chooser that
  had simply been switched off). Both take their CONTROL from the production chooser itself with
  `driftMoved=false`, drive a tower from whatever it hands back, and hard-fail the rig unless the
  flight comes back broken. Measured pre-fix: `subject.chosen=236196,223,100000` (the step) beside
  `subject.unpinned=null` — the unpinned path already refused, and only the pinned one did not.

  The ledge arm drives its tower from `subject.chosen` rather than from the ledge the scene staged.
  That is not tidiness: the pre-fix reproduction handed back the STEP and still reported
  `subject.after = 0 fault(s): 7 级都完好`, because the drive had been given the right cell by the
  test instead of by the code.

## 2026-08-19

- **The stride guard's lava break did NOT cost rung 12, and the four ladder runs say so without
  another playthrough.** The run after that fix regressed `wd.journey12PortalLit`, on the rung that
  works a lava lake's rim — the shape the fix's own caveat named. The guard logs every fire
  unconditionally, so the question is answerable off the logs: it fired **83 times in that run, all
  of them inside rung 12, and every one on two cells of the lake's rim** (`-10,64,16` ×75 and
  `-9,64,17` ×8). The previous run — same world, same rung, **PASS** — logged **zero** fires in that
  rung and its 43 fires all afterwards, in the Nether at `y=43`. So the fix is exactly what changed
  the guard's behaviour there.

  It is not what changed the verdict. The rung failed at `lava9.up` with the body on `1,60,19` —
  ten blocks and three minutes away from the last pin, which stopped at 08:22:32 while the failure
  landed at 08:25:28 — and the pins cost the leg they happened on **nothing measurable**: every
  `lava*.up` goto ran its full 600 ticks and ended at `-7,64,16` in the passing pre-fix run too
  (`身体=-8,64,16` at 200, `-7,64,16` at 400, cast after cast, in both runs). The prior FAIL of this
  rung, `走不回模腔：停在 -1, 59, 19`, is the same family and predates the fix by two runs with zero
  guard fires in the whole log.

  What did it: **`recover8.rise` towered up the staircase's own column.** The raise is
  ray-**pinned** to column `2,19`, and `offTheFlight` records rather than moves a pinned column —
  `⚠ 这一柱正是 2, 56, 19 那一级所在的柱 —— 只记下来`. Course 1 then drifted to `1,58,19`, could not
  walk back (`driftWedged`), and `driftKeptPinned` adopted column **`1,19`** — a *different* column,
  chosen by a drift, never once put to `towerColumnClearOfTheFlight`, and also a stair column. Two
  dirt went in at `1,58,19` and `1,59,19` and the body finished standing on them. `lava9.up`'s audit
  then mended the two blocked treads it could see (`1,57,19`, `2,56,19`) and not the block under the
  body's own feet, and all three ascent legs died. The pin's stated reason for not moving —「换了柱
  就等于换了射线」— is void the moment a drift changes the column anyway; that is where the flight
  check belongs and does not run. Open, not fixed here.

- **Two more arms for the same guard, asking what its refusal COSTS**, both `withRequired(false)`.
  `wd.serverStopsAtALavaShore` drives a body AT a lake and requires it to be stopped; there,
  stopping is the answer. `wd.serverKeepsWalkingAtALavaRim` walks a body PAST one — the ladder's own
  reading, a lateral drift toward the lake while the route runs along the rim — and requires three
  things, the third being the caveat's: it must not go in, the pin must be what held it, and **it
  must keep travelling along the rim afterwards**, measured from the first pinned tick because the
  four-cell run-up would otherwise carry a plain「walked N cells」clause on its own. Measured:
  control `36 tick，沿岸走了 8.32 格 … 脚下=lava，泡在岩浆里`; subject `200 tick … 沿岸走了 15.70
  格，第一次被钉住之后又走了 11.08 格，钉住 181 tick`. **Pinned for 181 of 200 ticks and eleven
  cells further along the rim** — the pin refuses the sideways step, not the journey.
  `wd.serverKeepsWalkingAtADryRim` is the same trench filled with stone, where the guard must stay
  silent: control and subject byte-identical at `67 tick … 钉住 0 tick`.

  Clause 3's failure is a property of the guard rather than of the arena, so the control arm cannot
  produce it and it was attacked directly instead: the bottomless branch's
  `setDeltaMovement(0, dy, 0)` was made unconditional — the strongest stop that guard can express —
  and the gate re-run. **It did not go red**: post-pin travel fell 11.08 → 7.53, still four times the
  bar, because zeroing the momentum costs one tick and vanilla's `maybeBackOffFromEdge` refuses only
  the component of a move that would leave the floor. The line was put back and the arm's javadoc
  carries the number rather than the assumption it replaced.

  The `streak` row is unconditional in both arms and it corrects the caveat's other half: the escape
  hatch `guardPinStreak >= 30` is reachable **or not depending on the approach**, not on the guard. A
  body pressing steadily at a rim pins every tick and sails past it (181 here, so the path really was
  dropped and re-searched). Rung 12's rim produced **bursts of five** — the pin decelerates the body
  under the guard's own `h ≥ 0.03`, the fires stop, the 8-tick hold tail expires and the streak
  resets at ~13. So「a sustained pin forces a repath and the crossing routes around」held in the
  arena and never once fired on the ladder.

- **The stride floor-guard could not see lava, so the Nether crossing walked into it four times.**
  Its fall scan stopped at the first cell that is not `isPassable` — and lava is passable, being
  neither solid nor water, so the scan descended straight through a lake and stopped on the bed
  underneath. Measured on rung 14's fatal cell: the lava starts 13 rows under the stride cell and
  the netherrack bed sits **exactly 23** rows under it, which is the loop's own reach at full health
  (`ceil(20)+3`). It found a floor on the last index it looks at and reported the stride safe.
  `WalkerGeometry.dropAdjacentExceeds` learned this in round52 and has carried the `isHazard` line
  since; this guard never got it, and now does.

  All three brakes were off on that tick and only one of them was wrong. `WalkerTickDrive`'s
  `edgeBrake` and `Walker.footingGuard` both released for a **planned descent** — the node they were
  steering at (`77,41,83[diagDown]`) sits one below the foot — which is deliberate and load-bearing
  (a pin held across a step the route means to take deadlocks the descent; `wd.descent`,
  `wd.bridgeDescend` and `wd.descentYaw` named that cost in one run). The stride guard is the one
  with no such release: its exemption demands the descending node be in the stride column exactly,
  and that plan was heading the other way. It was reached, it asked, and it got the wrong answer.

- **Two arms in a sealed arena for it**, both `withRequired(false)`, one headland, and the bay's fill
  as the only variable. `wd.serverStopsAtALavaShore` walks a body off a shelf into an eleven-block
  bay — a drop that is *survivable dry*, so the lava is the only lethal thing in the arena — and each
  arm drives that shelf twice with `walkerStrideFloorGuard` as the only difference between the
  drives. Before the fix, control and subject were byte-identical: `1 fault(s): 42 tick, 最低
  y=219.00, 沿台面走了 11.06 格, 钉住 0 tick, 脚下=lava`. After: `subject.after = 0 fault(s): 240
  tick, y=231.00, 走了 6.79 格, 钉住 219 tick`. `wd.serverWalksOffASurvivableLedge` is the same bay
  filled with stone and requires the guard to stay out of the way — control and subject identical at
  41 ticks and 0 pinned ticks, before and after — because a fix that pinned at every lip would pass
  the first arm and make ridge walking crawl.

  `lethalEdgeBrake` is off in both arms and that is the isolation rather than a shortcut: with it on,
  `footingGuard` pins this body as its sole thins and neither arm ever reaches the bay, so the scene
  would be measuring the guard that was already working.

- **The Nether crossing writes down its own arithmetic now** (`<what>.budget`, `<what>.pace`).
  A crossing that stops short gets accused of running out of budget, and that has now been wrong
  once: rung 14's 402-block leg needs ~10 hops of net 42 against a ceiling of `MAX_HOPS = 24`, and
  ~3 300 ticks against `24 × 900 = 21 600` and a rung budget of 360 000 — 2.4× the hops it needs.
  It stopped after three because the body was in lava and `hazardBlockingARetry` correctly refused a
  fourth hop. `pace` gives `全程无计划` the denominator it never had (67 of 1517 ticks is 4%, a
  rounding error; the same 67 out of 900 would be a re-planning problem), so the next reader chooses
  between "give it more budget" and "it cannot plan here" from a number instead of a hop line.

- **`wd.serverDrawsABow` stopped losing its own arrow.** A full draw leaves the string at 3 blocks
  per tick and the count ran ten server ticks later inside a 24-block box, so a working release
  reported `flew=0` beside `probe.flew=1` whenever the arrow flew straight — which is most of the
  time it is working. The box is now three times the arrow's reach.

- **The three `phase=JUMPING` rows of 2026-08-19 are now a sealed arena.** `vein2.exit#3.climb.1`,
  `crystal.0.climb` and the podium march all reported `stuck (no Y gain in 60t: placed=0,
  holding=64, phase=JUMPING, apexFeetY=<start>)` over a body that was on the ground, not in water,
  and holding a stack — a shape no caller can produce on purpose. Two new arms, both
  `withRequired(false)`, and in each the CONTROL is the arm carrying the fault:
  `wd.serverTowersUnderALowCeiling` puts stone at feet+2 (the cell a whole block of rise needs) and
  `wd.serverTowersUnderTheNeighboursCeiling` puts one stone cell over the column BESIDE the body,
  where `blockPosition().above(2)` reads air. Measured before any fix: `control.after = 1 fault(s)`,
  61 ticks of a 400-tick budget, `placed=0`, `spent=0`, `placeTally calls=0`, and an apex of `+0.20`
  — the jump fired and was clipped — against `subject.after = 0 fault(s)`, 4 of 4 courses, whose
  only difference is 0.45 of a block in x.

- **A tower whose jump does not clear a whole block no longer ends the order.** `TowerProcess` left
  its `JUMPING` phase on `p.getY() >= jumpFromY + 1.0` and on nothing else, and released the jump key
  on that phase's first tick — so a single short arc was a one-way door: the process spent every
  remaining tick of its caller's budget face-down over a cell it had already decided not to fill.
  `JUMPING` now returns to `READY` once the body is footed again and the rise never happened, and the
  stuck message carries `shortJumps` so「tried fifteen arcs」and「wedged on the first」stop printing
  alike. That wedge is what turned rung 12 into rung 9: the exit tower gained nothing, the walker
  fallback left the body three blocks DEEPER and in another column, and the rung after it failed for
  want of a free cell to stand a crafting table in.

- **And it asks, before jumping, whether the body can rise at all — with the body's own box.** New
  `WalkerGeometry.pillarRiseBlockers`: the cells a 0.6-wide body would have to lift itself through,
  decided by vanilla's own collision test and then NAMED. `blockPosition().above(2)` answers about
  one column, and a body standing within 0.3 of a cell boundary also lifts a corner of itself through
  the neighbour's — `wd.serverTowersUnderTheNeighboursCeiling`'s two runs differ by 0.45 of a block in
  x and by nothing else, and gain 0 of 4 courses versus 4 of 4. The verdict is now
  `blocked overhead (placed=0, feetY=221, 升不满一格：<cell>=<block>)` on the FIRST tick instead of a
  phase name sixty ticks later — cells, because this process places and never breaks, so the only
  thing it can do about a lid is name it for the caller that can mine it.

- **The mine exit clears what the body has to rise through, not what its coordinate names.**
  `JourneyShaft.ascendByTowering` opened `at.above(2)` before each course, which is exactly the shape
  that produces the wedge above: it clears the body's own column, the body gains its block and comes
  back down a fraction of a cell over, and the next course jumps into rock its own check has just
  reported clear. That is why `vein2.exit#3` stalled on course ONE, after course zero had gained. The
  course row's `above=` now lists every blocking cell, and the same predicate the builder refuses on
  decides what gets mined — a caller mining a different set from the one the process will refuse
  takes a course off the budget and changes nothing.

- **A climb that ends lower than it started says so, and tries once more.** `gained=-3/20` reads as a
  fraction like any other and went past every reader between `vein2.exit#3` and the rung that failed
  two legs later. The walker fallback is the only part of a climb that can move the body DOWN
  (`Goal.YLevel` is column-blind, so a route to it may descend first, and a failed search keeps
  whatever the partial path gave it); it now latches the height it was handed and, when it ends below
  it, records `fallbackWentDown` and re-enters the scripted ascent from where the body actually is. A
  genuinely different attempt rather than the same question twice: a different column, and a builder
  that now refuses the ceiling by name. `recordExit` additionally writes `<climb>.lost` whenever the
  gain is negative — one meaning, its own key, greppable across runs.

- **Rung 13 now walks into the portal by a row a body actually fits through, and opens one cell of
  the wall when no row is open.** The ladder lit its portal and then failed with
  `站在传送门里 1200 tick 没被送走`, over evidence that said the opposite: `stand.in =
  Block{minecraft:air}`, eight byte-identical legs, and a scene total of 451 ticks against a message
  quoting a 1200-tick budget. Three separate things were wrong. (1) The goal was the portal's bottom
  cell — the only one the pathfinder can accept, since every other cell's floor is another portal
  block — and rung 12's casting slag walls the alcove in front of it, with `allowBreak` off on this
  rung's baseline. (2) The one row whose front was open is the TOP row, and a body is 1.8 tall in a
  3-tall doorway, so standing there puts its head in the frame: measured as sixty ticks of held
  forward moving the body to `z = cellZ − 0.3` and stopping with `dm.z = 0.000`. (3) Every retry
  asked the identical question. The rung now costs each row by what would have to be mined to reach
  it, refuses the frame itself (mining obsidian to get in would put the portal out), opens the one
  cobblestone in front of the row it fits through, walks to that doorstep with the goal SHAPE
  alternating between `Goal.XZ` and `Goal.Block`, and pushes the last block by hand — the pathfinder
  can never make that move, because its destination has no floor.

- **The two portal failures are now two messages.** 「站进去了没被送走」and「一次都没站进过传送门方块」
  want opposite fixes — `Entity.handlePortal`, or the geometry in front of the door — so they are
  separate branches decided by whether the body was ever in a portal cell, and every tick count in
  either is summed from the level's own clock as the legs run rather than quoted from a budget. New
  evidence rows carry the whole doorway (`portal.doorway`: each cell, whether a body fits in it, and
  every approach cell with the reason it is not standable), the row chosen and what it costs
  (`portal.doorstep`), the walk legs with their goal shape and how far the body actually moved
  (`portal.walk.*`), and `portal.ticked` — the body's own `tickCount` delta across the hold, which is
  what lets the transfer branch say「the timer did not run」out loud instead of leaving「nobody was
  ticking it」open.

- The self-built server avatar now releases a held item through `LivingEntity.releaseUsingItem()`
  instead of `stopUsingItem()`. Only the former calls `ItemStack.releaseUsing`, which is where a bow
  spawns its arrow; the latter just clears `useItem` and the in-use flag. A bow drawn to full and
  "released" the old way was indistinguishable from one that fired — draw timer climbing, ammo
  untouched, no exception, and no projectile. Rung 20 spent four rounds of fixes on the draw, the
  aim and the ammo because of it. With the release routed correctly the End fight went 200 → 0 HP
  on 246 arrows and 0 melee swings, killing the dragon for the first time.
- The crystal sweep's unwedge tower now targets the island band absolutely instead of eight blocks
  above wherever the body stopped. The old form escalated across sweeps (103 → 111 → 119) and a
  tower only goes up, so each re-sweep started structurally further from a y=80 crystal than the
  one before it.

## [Unreleased]

### Added
- **The portal doorway is now a scene instead of a forty-minute ladder run.**
  `wd.portalEntryDigsIntoTheRowItFits` and `wd.portalEntryWillNotMineItsOwnFrame` stage the same lit
  portal walled into a hillside and differ in one thing: what the wall in front of it is made of.
  The first is rung 12's own output — slag over the bottom two rows, the top row open and too short
  for a body — and requires the subject to decline the open row, name the middle row and the single
  cell in its way, open it, walk there and end up inside a `nether_portal` cell. The second surrounds
  the doorway with the frame's own obsidian, where the cheapest thing to remove is always the portal
  itself, and requires the refusal. Each arm carries a control: the first drives the pre-fix goal
  (`Goal.Block` on the bottom cell) and requires the body to come back OUTSIDE the portal —
  `control.after = 1 fault(s)` before `subject.after = 0 fault(s)` — and the second stages two cells
  of that wall as stone and requires a way in to be found, so「obsidian means null」is a reading and
  not a constant. The arms also record the body to three decimals across the push: `z = cellZ − 0.3`
  with `dm.z = 0.000` is what「the head is in the frame」looks like, and no cell-resolution reading
  can tell it from「did not move at all」.

- **A body on a cut staircase, asked to unwedge, is now a scene rather than a forty-minute ladder
  run.** `wd.unwedgeRefusesTheStaircaseColumn` and `wd.unwedgeTowersBesideTheStaircase` stage the
  same seven-cell flight and differ in one thing — whether there is a standable cell beside the step
  the body is on. The first is the ladder's own geometry (stairwell cut through rock, nowhere to step
  aside to) and requires the refusal; the second adds a ledge and requires the tower to move there,
  climb its four courses, spend its four blocks, and leave every cell of the flight passable. Each arm
  first drives the pre-fix behaviour and **requires the flight to come back broken**: `faults()
  .isEmpty()` is satisfied by a tower that never reached the staircase, so an arm that cannot break it
  on purpose has not earned the right to report that it kept it intact.

- **The ground-jump gate now reports where it disagrees with `onGround`.** Swapping a predicate is
  only visible where the old and new answers differ, and a suite that reports PASS/FAIL cannot show
  that: 253 scenes moved one colour, and the one that moved (`wd.buriedOre`) was in no category
  anybody had named, because its riser is *dug at runtime* — nothing about that arena says "this
  scene jumps". So `ServerPlayerAvatar` logs the first disagreement per body per direction
  (`起跳闸分歧 站着却报没站` for a jump that now fires, `悬空却报站着` for one that no longer does),
  with the sole area, the exact y and the fall speed. Membership of the affected class becomes a
  measurement — a scene is in it iff one of those lines falls inside its window, whether or not its
  colour moved — instead of a guess from arena names.

- **Every jump request now names the call site that made it.** `Walker.avatarJump` became an instance
  method so it can latch, because the jump expression in `WalkerTickDrive` that three rounds of
  analysis treated as *the* source of jumps is one of eleven places that set the request —
  `WalkerTickClimb` alone has eight, and only five sites of the eleven label themselves with
  `jumpTag`. Eliminating branches inside one of eleven and naming the survivor was never a
  measurement, and it was wrong every time. The source is therefore taken from the stack rather than
  from a hand-kept list: a file and line cannot fall out of date when a twelfth site appears, and a
  site that never labelled itself still names itself. Printed with the waypoint and `wp.y − foot.y`
  beside it, since a level platform can still hold a waypoint above the feet — the path's next node
  is not the neighbouring cell. One line per event, capped per walker; the stack walk happens only on
  the lines actually emitted. The drive's label chain also ends in `其它` instead of a bare `"swim"`:
  a chain whose last arm is a real branch name silently relabels every unmatched case as that branch
  and can never report that the labels have fallen behind the expression.

- **The stuck-wiggle recovery hop now reports the two numbers its own safety gate turns on.** That
  gate suppresses the hop when a lethal drop sits within Chebyshev 2 of the foot, while the hop's
  own javadoc prices the arc it is guarding against at "~3 blocks" — so the guard's reach and the
  throw it guards against were never comparable, and neither number was ever printed. The boolean is
  now a ring distance thresholded at the radius (`WalkerGeometry.nearestLethalHopRing`, the gate's
  own per-column test lifted verbatim so the two cannot disagree about what a lethal drop is), and
  entering the stuck window logs the radius and the measured ring side by side, once per event
  rather than once per tick. Behaviour is term-for-term unchanged; the scan still runs only inside
  the window. It matters because rung 20's takeoff samples put the body airborne with an impulse
  seven ticks earlier, and eliminating every jump term that needs a riser or water leaves this hop
  as the only one that can fire on a flat dry level walk — an elimination that is reasoning until
  something measures it, and the rehearsal log has no per-tick walker lines to measure it with.

- **A server body now says when it breaks the block it is standing on.** Asked with the same
  predicate the ground gate uses, before and after the destroy, so there is no second notion of
  "standing" to keep in sync: sole area `> 0` then `0` means the block that vanished was the one
  carrying this body. It earns a line because `wd.buriedOre` regressed on exactly that, and the
  evidence otherwise stops one step short: the disagreement reading pinned the tick
  (`悬空却报站着 t=260 脚底实心=0.0000 y=223.0000 落速=-0.0784` — flush at a block boundary, falling
  at precisely one tick of gravity from rest, so it *had* been resting on that support the tick
  before) but could not name which break took it. Deliberately a report and not a guard: a body may
  dig its own floor on purpose, and a silent "skip that break" would be one more fallback that
  ignores the invariant instead of surfacing it.

- **`Avatar#dbgLastJumpTick()`**, and the parkour takeoff latch now prints the absolute game tick
  beside each sample. The latch lives in the drive tail, which a dozen branches return before
  reaching, so its samples are not consecutive ticks and the phrase "N ticks before takeoff" had no
  meaning without the tick. The new field answers the question the first End-rung reading raised and
  could not settle: a takeoff sample that says the body was already airborne cannot say whether the
  body *jumped itself* off the platform (an impulse a few ticks earlier, so the parkour edge became
  current mid-arc) or simply walked off the lip (no impulse at all). `dbgLastJumpTick` is the tick an
  impulse was **emitted**, not one where a jump was asked for — the walker holds jump for runs of
  ticks and the body's own gate decides which of them become an impulse, and that difference is
  exactly the reading.

### Fixed

**The journey's unwedge tower no longer builds in the staircase's own column.** Rung 12's recovery
pillared from wherever the body was standing, and on 2026-08-19 the body was standing on step seven
of the flight it had just cut: `cast8.returnStuck2#9.column = 0,19`, `climb.0 = 0,58,19`, and the
next audit read `2/11 级坏了：0, 58, 19 挡住 …=cobblestone，1, 57, 19 挡住 1, 58, 19=cobblestone`.
`TowerProcess` fills the cell the body jumped FROM, so a climb started in a flight column walls that
flight up course after course without ever choosing a cell — and the mend could not break back
through them (`敲不开（身体 -1, 59, 19）`), so the rung died with `走不回模腔：停在 -1, 59, 19`. Every
climb now picks its column through `JourneyShaft.towerColumnClearOfTheFlight` and records the answer
unconditionally (`<climb>.offTheFlight`), at **both** entry points and again where the drift
correction adopts a column — an invariant one entry enforces is not enforced, which this class has
already paid for twice over the column and the pin. The chooser is three-valued: the body's own
column when no flight runs through it, the nearest standable off-flight column when one exists, and
**null**, meaning *do not tower here at all*. Null is the ladder's common case rather than its rare
one — a flight cut into rock has wall on both sides — so a fallback that towered anyway would be a
rule bypassed on every run; those climbs now walk the flight instead, placing and breaking nothing.

**Rung 12 now says when the foot of its own staircase is under water, and waits for it.** The alcove's
floor row is the flight's bottom row, so a cast source floods the corridor and runs down the stairs:
`cast8.landing = … 楼梯底 2, 56, 19=water（流动），其上 2, 57, 19=water（流动）`. That reading was buried
mid-format-string in a row about the body, and `drainTheAlcove` certified `壁龛已排干` beside it,
because the bottom step is not a corridor cell. The flood now has its own row on every return
(`cast*.stairFoot`) and the drain waits on the stairwell's three foot cells as well as on the corridor
(`drain.N.stairFoot`). Recording and waiting, deliberately, not repairing: a pick does not remove
water, and the one verb that does — a placement — would fill the bottom step, which is the same
broken stair the tower had just caused.

**The void rule now covers every move that crosses a corner or a gap, not the subset each patch was
written against.** Three times in two days the same shape leaked back: the leap gate went to
`Parkour2/2Diagonal/3` while `Parkour3Diagonal/4/Ascend/Descend` stayed open; the corner gate went
to `Diagonal` while `DiagonalAscend/Descend` stayed open. Each time rung 20 resumed leaving the
world from whichever cells the closed members never covered — island rims at
`-15,60,36 / -16,61,34 / -18,61,36 / -35,62,-6`, tower tops at `-33,82,26 / -35,82,26`. With the
families closed, `wd.serverWidensAThinFooting` and `wd.serverWidensFromTheBackpack` — written RED by
design, with a second clause specifically so that standing perfectly still could not score full
marks — are **GREEN**. Also braked two carriers of residual momentum that no input release reaches:
the stride guard over a bottomless column, and `TowerProcess` at the tick it asks for its jump
(「under SETTLE_SPEED」is not zero, and the remainder rides the whole arc).



**A leap over a bottomless gap is now refused outright when the body could bridge instead.** The
cost model cannot express this preference: `parkour3` is 32 and the bridge chain that replaces it is
80+80+10 = 170, so a price change would have to put a placed block under 11 — below `walk` itself,
and 80 is exactly what killed「深谷凌空架桥」when it was raised from 30. The two mistakes are also
not symmetric. Misjudging a leap over a 3-deep pit costs a climb out; misjudging one over the void
ends the run, because this body's `isInvulnerableTo` is permanently true — it does not die and
respawn, it falls forever, and every order after that is issued to a body in the void. Rung 20 has
ended that way repeatedly (`身体掉出世界 y=-65, 位置 -61,-65,16, 已砸碎 5/10 座`). The rule applies
only while `allowPlace` is on: with placement off the leap is still the best move available, and a
guard that leaves a body with no move at all is not an improvement.



**A 1-cell pad over the void planned a leap priced at the sprint-jump maximum.** `Move.hasRunway`
looked under the launch foot and nothing else, modelling no momentum at all, so A* chained a
`parkour3` from a standing start it could not physically cover. `wd.parkourVoidRunwayGate` has been
red by design since it was written, with two arms over the identical gap differing only in run-up
length and both planning the identical move; it is **GREEN** now. Only the 3-block leap asks the new
direction-aware form — a 2-block gap is inside a standing jump, and a guard that refuses what works
replaces a route with a worse one rather than a safer one. Rung 20 had been paying for this by
walking off ledges into the void: 8/10 crystals smashed and then `身体掉出世界 y=-67`.



**`holdPlaceable` stopped at slot 8, so a body with a full bag was "out of blocks".** The server
avatar's scan looked at the hand and hotbar slots 0..8 and gave up, while the tool selector directly
below it in the same class has always swapped up from the bag. Two arms of `wd.serverWidens*` differ
by exactly one variable and nothing else: 64 cobblestone in slot 0 → the footing remedy spends a
block and the sole one tick later goes **0.168 → 0.360**; the same stack in slot 20 → **zero** blocks
spent, sole 0.168 → 0.184, body off the ledge. With the bag in scope the backpack arm reads
identically to the hotbar arm. This is why rung 20 logged five footing pins and not one 垫脚: the
body walks its End legs with the haul wherever picking it up put it. No other scene changed colour.



**`Walker.widenFooting` was never observed to fire, and the arena was why.** The remedy only spends
a block over a column that is bottomless all the way to `BOTTOMLESS_SCAN_FLOOR` (-70), and rightly
so — over an ordinary drop a thin sole is a graze, and paying a block per ridge walk eats a bridging
contract. The first cut of `wd.serverWidensAThinFooting` put a catch floor 30 cells under the ledge
for tidiness, so the column read as ordinary ground and the remedy declined. With a genuine shaft
under the footprint the same arm reads **1 cobblestone spent** and the sole one tick later goes
**0.168 → 0.360** instead of 0.168 → 0.184. The arm was measuring its own arena, not the subject.



**The footing guard asked `onGround()` before it asked the world.** Its first line returned on
`!p.onGround()`, which is `verticalCollisionBelow` — a report on the last `move()`, not on what is
under the body. The guard's worst ticks are precisely the ones with no informative last move (after
a placement, after a jump, after a reposition), and the very next line already read the sole from
the world, so the flag contributed no fact and only false negatives. Replaced with a single sole
read: a zero sole still returns, because a body with nothing under it is falling and sneak is a
refusal to step further out, not a rescue. **Unverified as a remedy**: the arena built for this cell
(`wd.serverWidensAThinFooting`) reads byte-identically before and after — 18 ticks, same exit
coordinates, `widen=0` — so something else is keeping the guard out of this path, and this change is
recorded as removing a known-bad read rather than as a fix. No other scene changed colour.


- **A tower begun at the end of a walk no longer walks off the column it is filling.** `TowerProcess`
  jumps, waits for the feet to clear the cell, then fills it — and `Avatar.releaseInputs` clears
  forward/sneak/jump but touches neither the velocity already in the body nor the sprint FLAG. A body
  that arrives walking therefore crossed a cell boundary inside its own course, so the fill landed in
  a column it was no longer over and the next course started from a cell with nothing under it.
  Measured by `wd.serverTowersAfterAWalk`, whose ONLY difference from the green
  `wd.serverTowersTwelveCourses` is that the body arrives walking: it spent 3 blocks, put 0 of them in
  the target column, drifted 4 cells and fell 39. Journey rung 20 had been paying the same bill in the
  open — one climb spent 13 cobblestone for 5 blocks of height with 933 still in the bag, another
  ended at y=-17 while its target was y=81. Three changes, each independently insufficient: a course
  waits for horizontal speed to fall under 0.05 (ground friction takes a walk's 0.156 there in four
  ticks; a 20-tick backstop keeps a body something else is pushing from hanging); the column is
  latched with `jumpFromY` at the jump instead of recomputed from the body's current x/z, which had
  the two halves of one coordinate describing two different moments; and the sprint flag is cleared
  before the jump, because vanilla adds +0.2 along the yaw on top of the 0.42 whenever
  `isSprinting()` and a tower is a purely vertical move. Cleared inside the process rather than in
  `releaseInputs`, which is a default on every avatar and which the Walker rewrites every tick anyway.
  After: `climbed 8, drift 0,0, spent 8, 8 solid` with the body still arriving at `h=0.1563`.

- **A tower asks its own sole whether it may start a course, not `onGround`.** `onGround` is
  `verticalCollisionBelow` — it describes the last `move()` and is wrong in both directions.
  `ServerPlayerAvatar`'s jump gate abandoned it for `WalkerGeometry.soleOnSolid` for exactly that
  reason, and `wd.flushJumpIgnoresOnGround` already pinned that a body can be flush on stone with
  `onGround` false; `TowerProcess` was the last reader of it, which made a tower refuse to start on a
  footing the engine was perfectly happy to jump from. Measured by `wd.serverTowersWithoutOnGround`,
  which forces the flag false every tick: before, 60 ticks and zero blocks; after, four courses and
  four blocks, with the sole never leaving the stone in either case.

- **A tower that was never needed no longer reports the same thing as a tower that built.** Both said
  `done (placed=N)`, so four consecutive climbs on journey rung 20 printed「到顶」while placing
  nothing — the body was already above its target every time, and the rows that said so were
  indistinguishable from rows describing a climb. The tower had therefore never once been exercised in
  the shape that rung uses it, and no reading could have said so. It now answers
  `not needed (feetY=… already ≥ targetY=…)`. `BotApiImpl` already refuses this argument outright
  ("target Y must be > current feet Y"); the constructor cannot, because the starting feet are not
  known until the first tick.

- **A stuck tower reports what it knows instead of guessing it ran out of blocks.**
  `stuck (no Y gain in 60t — out of blocks?)` was printed while the body held a full stack — on rung
  20, while holding 933 cobblestone — and a guess written into a product message gets read as a
  measurement by whoever finds it next. It now carries `placed`, the held count, the phase and the
  apex, which separate the three real causes: nothing to place, a jump that never cleared its own cell
  (the phase stays `JUMPING`), and a body being carried off its own column.

- **A body in mid-air could spend a path node on a climb it had not made, and nothing could report
  it.** Every one of the nine step-advance gates answers "has the body reached this node?" with a
  horizontal test and a vertical test taken at the body's *current* y — and mid-jump, the current y
  is a claim about the apex, not about where the body will be standing. `wd.buriedOre` advanced four
  consecutive nodes while airborne, the third of them reading

  ```
  序=3 因=within 旧步=3 新步=4 w=106659,223,99999 nx=106660,224,100000
       身体精确=(106659.527,223.252,100000.151) cur2=0.425  (< REACH_DIST_SQ=0.45)
       |w.y-p.y|=0.252 |nx.y-p.y|=0.748 onGround=false 脚底实心=0.0000
  ```

  The advance was `within`, whose vertical clause is `|dyNode| < 1.2` and which has no ground test of
  any kind. The body landed back at y=222, leaving the pointer at a node two blocks above its feet —
  a `+2` that `StepUp2`'s own gate states A* never plans — and it jumped at that node for the rest of
  the scene.

  The refusal sits at the single `step++`, so `within`, `passed`, the tail consume and the arc
  projection are all covered by one predicate that cannot drift out of step with them: don't consume
  a node whose successor is *higher* while the sole is off solid ground. Dry land only — a buoyant
  body reads no footing for the whole of every water crossing, so the same guard applied there would
  freeze surface swimming outright rather than occasionally. Footing is `soleOnSolid < FOOTING_MIN`,
  the predicate the ground-jump gate and the footing guard already use, rather than `!onGround()`,
  which describes the previous `move()` and is wrong in both directions.

  Part of the effect is to *move* the failure, which is the point. The walker now keeps driving the
  body at a cell the arena's own audit calls standable, and if it truly cannot get there
  `noStepProgressTicks` accumulates and the wedge/repath machinery takes over. The old behaviour was
  a silent chase of an unreachable target — silent precisely because every counter that could have
  complained was watching a pointer that kept advancing. Two siblings are left standing and remain
  suspect: `within`'s `|dyNode| < 1.2`, and `stepUpCrestReach`'s `|dyNode| < 0.5`, which is the one
  relaxed-advance gate with no `nx` reachability clause at all.

- **A run-up made a leap over void *less* likely to clear it, because the lip switched off the
  sprint.** The lethal-edge sprint brake asks `lethalDropAdjacent(world, p, foot)` — does any of the
  *foot cell's* eight horizontal neighbours drop further than `survivableFall` — and its only
  exemption was `parkourAscend`, i.e. `parkourEdge && wp.y > foot.y`, rising leaps. A flat leap is
  identically false there, and `lethalNear` is only ever true over a drop, so the one class of jump
  that cannot be finished without momentum was the one class that never got any. Which cell the body
  launched from decided it: two scenes with the same geometry and different run-up lengths split
  cleanly, `wd.parkourVoidShortRunway` taking off one cell *behind* the lip (all eight neighbours on
  the pad, `h 0.1232 → 0.2475`, the impulse fires) and `wd.parkourVoidLongRunway` taking off *on* it
  (`h 0.1563 → 0.1400`, x0.896 air decay, no impulse, into the gap). More runway means a fuller
  approach means the body ends up standing on the lip, so the run-up was not a second-order help —
  it was the thing that broke the leap.

  The exemption is now keyed on `parkourEdge`. Narrowed to the leap rather than loosened to a
  blanket `!lethalNear`, because the non-parkour half of that predicate is load-bearing: a plain
  walk-off lip must still lose its sprint, which is what `wd.bridgeLethalGapStop` exists to hold.
  The dynamic sneak brake gets the same exclusion — on the lip, `edgeBrake` held shift through the
  takeoff and `ServerPlayerAvatar`'s `pendingSneak ? 0.3f : 1f` then served the leap 30% of its
  steering, so restoring sprint alone would have been half a fix. Both execution arms of the
  parkour-void family are promoted to required; the planner arm (`wd.parkourVoidRunwayGate`) stays
  optional, since nothing here touches the run-up modelling it measures.

- **The ground gate's fall-speed term refused a body that was standing, and shipped with nothing
  able to say so.** The gate was `soleOnSolid > 0 && deltaMovement.y <= 0`; the second term was added
  for buoyancy — a body carried *up* through a block boundary is touching the floor, not standing on
  it, and must keep its 0.04 bob rather than take a 0.42 jump. The motive is right and `dy` is the
  wrong quantity for it. `LivingEntity.handleRelativeFrictionAndCalculateMovement` rewrites the
  post-move vertical component to `+0.2` whenever `(horizontalCollision || jumping)` and the body is
  on a climbable, and `ServerPlayerAvatar` mirrors `fp.jumping = pendingJump` every tick — so merely
  *asking* for a jump arms the rewrite, a body standing on rock in a ladder cell reads `dy > 0`
  forever, and after its first jump it can never jump again. The term conflated "the world is lifting
  me" with "I am on a ladder holding jump", and only the first was ever meant.

  It is deleted rather than replaced, because the flush-contact test already carries the motive:
  `soleOnSolid` reads the row `floor(minY − 1e-7)`, the row the sole *sits on*, so a body held up by
  water is flush with nothing and answers 0. The only way a body in water answers `> 0` is by resting
  on the bottom — which is the shallow-water ground jump this branch has always promised.

  Both halves are now pinned by arenas built to fail in opposite directions.
  `wd.climbableGroundJump` stands a body on rock beside a ladder (untouched) and inside one (armed),
  and requires more than one jump from each while the ask is held. `wd.buoyantJumpStaysABob` floats a
  body over five blocks of water — every rise must stay bob-sized, so widening the support test or
  adding a "can't tell, call it standing" fallback turns it red — and rests another on the bottom of
  a one-deep pool, which must still make a 0.42, so refusing all jumps in water turns *that* red. The
  discriminator needs no internal state: a single-tick rise above 0.3 can only be the ground jump.

- **A server-driven body asked the wrong question about whether it was standing, so a planned leap
  went unjumped.** `ServerPlayerAvatar.step()` gated its ground jump — vanilla's `+0.42` plus the
  whole sprint forward boost — on `fp.onGround()`. Decompiled from the 1.21.1 named jar,
  `Entity.move` ends in

  ```java
  this.verticalCollisionBelow = this.verticalCollision && pos.y < 0.0;
  this.setOnGroundWithMovement(this.verticalCollisionBelow, vec3);
  ```

  so `onGround()` is not an independent reading at all: it *is* `verticalCollisionBelow`, i.e. "the
  move I asked for last was downward and something clipped it". That is a claim about the previous
  move, not about where the body is, and it is wrong in both directions. It is **false about a body
  that is standing** when the body landed flush (the requested drop fitted, leaving nothing to clip)
  or was set into place instead of moved — and for a driven body, `Entity.move` and
  `ServerGamePacketListenerImpl` are the only writers of that field in the whole game, so nothing
  else was going to correct it. It is **true about a body that is not**, on the tick a fall is
  clipped at the start and the horizontal half then carries the body off the lip; that one was
  already measured beside a nether lava lake as `实心接触面积 0.0000/0.36` with `onGround` true.

  Both directions cost a leap. The false one refused the impulse on rung 20's planned `parkour3`,
  which `Parkour3` prices as a *sprint-jump maximum*, so the body walked off the platform edge at
  0.216/tick into the two-cell gap the plan meant it to clear, landed outside
  `EndDragonFight.validPlayer`'s 192 blocks, and the fight was never created. The true one fired
  `+0.42` off a lip into lava.

  The gate now asks the world instead of the bookkeeping: `WalkerGeometry.soleOnSolid > 0` — how
  much of the body's own 0.6-wide sole overlaps a solid block in the row its box sits on — plus
  vanilla's own other term, `deltaMovement.y <= 0`, so a body being carried *up* through a block
  boundary (buoyancy at a surface, a slime bounce) is still touching rather than standing. That is
  the same reading `Walker#footingGuard` already steers by, so the executor and the guard cannot
  drift apart; no new notion of "standing" was introduced, and `WalkerGeometry` became public for
  exactly that reason. `ClientPlayerAvatar` is untouched — it writes the real input and vanilla's
  own `aiStep` runs the gate there.

  Two scenes now hold the gate from both sides, because 222 of them watched only jumps that were
  supposed to happen: `wd.airborneJumpInert` (jump held for ten airborne ticks, y must never rise —
  this one passes before and after) and `wd.flushJumpIgnoresOnGround` (sole flush on stone with
  `setOnGround(false)` forced, the jump must still fire).

- **Rung 12 chose where to pour from an eye no body ever has, and the cell it cost was decided long
  before the bucket was spent.** `standToAimAt` weighed each candidate by clipping from the exact
  centre of the cell, at a height guessed by adding a whole block when the cell holds fluid. A real
  body is at neither: its box is 0.6 wide, so its centre rests anywhere in `[0.2, 0.8]` of its own
  cell — the walker leaves it wherever the last path edge ended, which `JourneyRamp#approach` had
  already measured from the other side — and one block of water floats it about a third of a block,
  not a whole one. Measured on the south rehearsal of 2026-08-17, cell eight:

  ```
  cast8.stand.3 = -10, 56, 32 瞄 -9, 60, 35（背板近面） 否决计数 {…}
  cast8.picks.3 = -9, 58, 33 Block{minecraft:cobblestone} face=west → 落进 -10, 58, 33
                  （…，身体 -10, 56, 32，眼睛 -9.15/57.98/32.57 …）
  ```

  The eye it was chosen for is `-9.50/58.62/32.50`; the eye that fired is `-9.15/57.98/32.57`. The
  shot is a diagonal (`dx=1, dz=3`), so a third of a block of x is a whole column of crossings.
  Traced against the block states that same run recorded, the centre eye crosses
  `(-10,58,32) (-10,58,33) (-10,59,33) (-9,59,34) (-9,60,34) (-9,60,35)` and reaches the backing,
  while the eye offset to `x=0.8` crosses `… (-9,58,33) (-9,59,33) …` — cell for cell the list the
  measured eye produced, and `-9,58,33` is the cobblestone `.picks` stopped on. Both archived
  refusals of that cell (`.picks.2` stopped on `-9,59,33`) are reproducible from the archive alone.

  The `.picks` gate caught the bad shot, as it is built to. What it could not catch is that
  `standLevelWith` asks this same chooser whether a raise is needed at all and skips the raise on a
  yes — so a maybe there costs the cell, not an approach. The whole south failure hung off that one
  answer: raise skipped, walk to a floating cell out of column, three refused picks, `liftInPlace`,
  an UNPINNED tower from a body that never lands (`climb.0 … onGround=false`, eight tries), and
  `liftedY = 58/59` for the ray gate to correctly refuse. The east arm, which has no such spot at
  that cell, raises instead and pours it 5 times in 5.

  So a stand is graded rather than accepted (`JourneySight.pourGrade`): the existing clip is re-asked
  from the four corners of the body's own footprint and, in fluid, at both heights a floating body
  can sit at. `ANYWHERE` is a verified stand; `CENTRE_ONLY` stays usable as a place to WALK to — the
  `.picks` gate is what spends the bucket, and refusing it outright would leave a body with nowhere
  to go — but it is no answer to「要不要垒台阶」. This widens nothing: it asks the same question more
  times, and an axis-aligned shot, which is the shape of every successful cast in this rung, is
  unaffected by construction, because sliding the eye along its own axis does not change which cells
  the line crosses. It is a new file because `JourneyPortalRung.java` stood at exactly the 3000-line
  budget.

  **That grade alone made the rung raise for cells that needed no raise, and a raise is not free.**
  Measured immediately, south run 2 of the new tree: casts 0–7 all `CONSUME` — the cell that had been
  the deterministic wall now pours — and the run died climbing home,
  `走不上楼梯：停在 -7, 59, 32 … 楼梯自检：1/12 级坏了：-9, 56, 32 挡住 -9, 57, 32=Block{minecraft:cobblestone}`.
  On the PRE-change tree that cell had never raised at all: it poured off the alcove floor through the
  real-ray short-circuit, `cast7.fromHere.3 = -9, 56, 32 就地瞄 -11, 59, 35，流体会落进 -11, 59, 34（不走了）`
  → `CONSUME`. The order was the defect and the stricter grade is what made it bite:
  `placeFluid` → `mendBacking` → `standLevelWith` (predict where the body COULD stand) → and only then
  `aimThatLandsIn` (fire the real ray from where the body IS). 权威的测试跑在它本该决定的那个决定之后。
  `standLevelWith` now asks the real ray first, so a raise is conditional on the pour being impossible
  from here rather than on any prediction about elsewhere. Checked against the archive before it was
  run: the old `cast7` has a `fromHere` row, so it stops raising; the old `cast8` has none at all and
  three refused picks, so it still raises, which is the change that made that cell pour.

### Changed
- **`JourneyPortalRung` split along the pour/scoop seam; `JourneyPour` is the pour half.** Pure
  relocation, done before the measuring runs rather than after, because a file at exactly its budget
  schedules its own next edit at the most expensive moment available: this rung's verdicts are read as
  distributions, a distribution is void the moment the tree changes, and a split after five runs would
  have voided five. Before: 3000/3000 lines, zero headroom. After: **2454 for `JourneyPortalRung`,
  577 for `JourneyPour`** — 546 lines of headroom, deliberately not shaved closer, since splitting to
  2900 would only re-arm the same trap.

  The seam already existed: `JourneyFill` is the scoop half and was split out first, and the two ask
  opposite questions of the same geometry (`Fluid.NONE` against the target's backing, against
  `Fluid.SOURCE_ONLY` into the target's own fluid). No new abstraction was invented to make the count
  work. Nothing changed in the move — the only edits are the qualifications a second file forces
  (`JourneyPortalRung.forgeCorridor`, `JourneyPortalRung.POUR_LINE`, the members the rung still calls
  becoming package-private) and one continuation line's indentation.

  **How to verify a split is a pure move, and it is not "I read the diff".** Undo the qualifications
  the split forced, re-insert the moved block at the offset it came from, and diff the reconstruction
  against the parent commit: a pure move reconstitutes the old file exactly, so every surviving hunk
  must be one you can name. Here that was seven — three forced by the split, four being the two
  deliberate behaviour edits above — and no unaccounted hunk. Reading the diff of a 545-line move
  cannot distinguish "moved" from "moved and quietly altered"; this can, and it costs one script.
  It also catches what eyes do not: the `PourSpot`-returning `standToPour` overload kept its `private`
  through the move and failed at compile time, which is the same slip one line further along a
  `void` signature would have hidden.

### Known, measured, not fixed
- **`JourneyStairs.needsOpen` is honoured by the ramp and bypassed by the tower behind it.**
  `JourneyRamp.fillable` refuses to lay a step into the descent flight; `JourneyShaft.climbOut*` →
  `TowerProcess`, which runs when that refusal ends the flight, places under itself and has never
  heard of a staircase. So the fallback does the thing the rule just refused:
  `cast7.ramp.noFlight = … -9, 57, 32 是下井楼梯 -9, 56, 32 那一级的头顶格，不能堵`, and the tower that
  took over filled precisely `-9,57,32`. Both south runs of the first post-change tree hit it; one
  survived because the audit's mend landed before the ascent and one did not, which is luck rather
  than safety. Recorded rather than repaired: the same decision point had already been edited twice
  in that session, and a third edit would have voided the distribution being measured. The general
  shape — a guard only the polite path consults — is written onto `needsOpen` itself.
- **Rung 12's staircase could hold two steps in one column, and the repair that answered it was what
  broke the staircase.** `digStairsDown` measured each course from `blockPosition()`. A step is opened
  by removing the floor of the cell the body is about to occupy, so the reading taken right after is
  often of a body one row ABOVE that step, falling into it — and a course measured from up there is
  one row too high, while the course after it, taken once the body has landed, puts its step in the
  same column. Both arms of 2026-08-17's handover carry exactly that pair, and they are the only two
  flights in this rung's whole archive that do (21 archived FAILs cut a staircase; the other 19 read
  `N 级都完好` at cut time):

  ```
  stair.3 = -8, 66, 19 → -6, 64, 19（一次挖两级）   ← the step is cut at y=64
  stair.4 = -6, 65, 19 → -5, 64, 19                 ← read from its HEAD ROOM, one row high
  stair.5 = -6, 64, 19 → -5, 63, 19                 ← same column as the step above it
  ```

  A flight shaped like that is a contradiction no world state satisfies: `JourneyStairs.faults` wants
  the upper step's support solid and the lower step's own cell open, and they are one cell. So the
  audit could never fall silent, and every leg spent a mend flipping that cell — measured on the south
  geometry, **18 audits and 16 mends**, alternating `cast*.stairsMend.0 = … → 垫上了` against
  `lava*.stairsMend.0 = … → 敲开了`. On east the fill is what ended the run:
  `cast6.returnStopped = 停在 -5, 64, 19 … 脚下 cobblestone` is the body standing on the cobblestone
  its own repair had just dropped into the staircase, facing a two-block drop where a step used to be.
  Physically the flight was fine — column x=-5 was a four-tall air shaft on native rock — until the
  mend filled it.

  The course is therefore anchored to the flight (`JourneyStairs.courseFrom`): when the body is
  directly over the flight's own deepest step, that step is where the next course starts. It widens
  no judgement — the cell it names is the one the body is falling into — and it says so when it fires
  (`stair.N.fromStep`). **Anchoring the LAST course is wrong**, though, and that cost two runs to
  learn: it aims below the target row, and the first attempt at handling that instead declared the
  anchored cell the bottom and waited for the body to drop into it. The body never dropped — it was
  standing on a step that had not opened — and the wait had no escape, so the run spent its whole
  40 000-tick budget with no `stairs.bottom` at all (`TIMEOUT 40001t`, last row
  `stair.9.waited = -9, 57, 31 还没迈下去`). Declaring the bottom from the anchored cell is also what
  put `stairs.bottom = -9, 56, 31` against `forge.landedY = 57`, an alcove hollowed one row above its
  own staircase, twice; the second of those runs then failed on the first waypoint of the first
  ascent, `第 0/3 段：想到 -9, 56, 31，停在 -9, 61, 32`. Both are answered by the same two lines: the
  bottom is the BODY's cell, always, and the anchor is dropped once it would reach the target row —
  which lets the last course be cut exactly as before (`-9, 57, 31 → -9, 56, 32`, one cell along the
  other axis), the course every healthy south run in the archive got to the floor by.

  Measured, ten pinned rehearsals: the east arm (`-PshaftColumn=-8,19`) is **5 PASS in 5**, each
  `frame.cast = 10/10`, `portal.cells = 6/6`, ten `recover*.result = CONSUME`, against 0 in 3 on the
  parent tree and 1 in 5 historically. `stairs.asCut` reads `N 级都完好` in every run, no flight
  carries a stacked column, and **`stairs.audit` reports 21–23 checks and 0 mends** where the handed-
  over south run reported 16.
- **The alcove's raise built into cells the descent flight needs open, and the flight's own audit then
  took the raise out from under the body.** `JourneyRamp.fillable` asked `JourneyStairs.cells
  .contains(c)`, and `cells` holds one cell per step — but `digStairsDown` cuts THREE (the step, its
  head room, and the clearance a climb jumps through) and `faults` audits all three. The alcove's
  floor row IS the flight's bottom step's row, so a raise out of the alcove starts beside that step
  and rises straight through its clearance. Measured on the south geometry: the raise laid
  `wet.8.ramp.flight = -7,56,32 → -8,57,32 → -9,58,32 → -9,59,33`, whose third block is
  `stairs.bottom(-9,56,32).above(2)`; the next audit read it as a broken stair, which it genuinely
  was, and mended it the only way a blocked cell can be mended —
  `lava8.stairsMend.1 = -9, 56, 32 起跳格 -9, 58, 32=cobblestone → 敲开了`. The run ended with the body
  in the flooded mould, `lava8.upStopped = 停在 -9, 58, 34 … 脚下/身处/头顶 都是 water`.

  `JourneyStairs.needsOpen` now answers for all three cells and `flightCell` names which one, so a
  refusal reads `2, 58, 19 是下井楼梯 2, 56, 19 那一级的起跳格（爬上去要从这里穿过），不能堵` instead of
  a sentence about the alcove's walls.

  *What it cost, said plainly:* across the nine rehearsals that finished, `buildTo` was called **61
  times and refused 11 before laying a block — 7 of those 11 are this new rule**, so the old kind of
  refusal is down to 4/61 (7%) from 12/22 (55%) in the archive. **27 of 61 landed on the exact row AND
  column (44%); 38 of 61 landed on the exact row (62%).** The east arm passes with the refusal in it
  (`cast8.ramp.noFlight` fires in all five). The south geometry does not: the lava cast for cell eight
  asks for a landing at `-9,59,32`, whose support is that same clearance cell, and the tower fallback
  cannot start in the flooded alcove
  (`cast8.lift#1.climb.8.afloat = -10, 56, 32 浮在水里，8 次都没落地`), so it stops one row short
  (`cast8.liftedY = 58/59`) and the pour's ray gate correctly refuses. Two runs, the same rows.
  **The refusal is right and the landing is what should move** — a legal column one rank over is built
  successfully in the same run (`cell.8.ramp.rampedY = 59/59（同一柱）`).
  said why named the wrong thing twice over.** The raise that puts the body level with the mould's
  top rows is `JourneyRamp.buildTo`, and reading the archive rather than running anything says it
  was mostly not raising at all. Over the four distinct ladder runs in `fabric/run-journey/logs` that
  carry its rows, it was called **22 times, refused 12 of them before laying a single block, and all
  12 refusals were the same sentence** — the landing's own support with six air neighbours:

  ```
  water8.ramp.noFlight = 6, 60, 19 修不出楼梯：这一格自己的垫脚 6, 59, 19 垫不了：
                         6, 59, 19 六邻没有能贴的实心面（放方块要贴着一个面点）：
                         down=air up=air north=air south=air west=air east=air
  ```

  A refusal there is not a fallback: the two things behind it are the scripted tower, which stalls on
  this geometry, and the walker's Y-level goal, which on the same run left the body seven columns out
  of the one the aim was computed for (`water8#3.endedIn = 0,19（起塔柱是 6,19 —— 不是同一柱）`). So
  the pour or the scoop that followed fired a ray nobody had verified, and the failure surfaced as a
  frame the rung would not break or a bucket that would not fill. **5 of those 22 calls landed on the
  row AND the column asked for.**

  What the test missed is that a flight is laid bottom-up and one cell of it is always face-adjacent
  to the course below. The steps are not — `support(i+1) - support(i)` is a diagonal by construction,
  which is why a staircase alone can never hold itself up in a hollow box — but the block directly
  UNDER a step lies in the previous step's own row, one cell along it. So the flight now hands itself
  the face it needs: lay that shoulder against the step below, then lay the step against the
  shoulder. Only the bottom course still borrows a face from the world, and its support rests on the
  rock under the alcove, so it always has one. Two blocks a course out of the ninety-odd cobblestone
  the rung already carries; both are registered as steps, because a shoulder is as much floor as the
  step on top of it and `tidyTheAlcove` would otherwise sweep it. A shoulder that would land in a
  cell the flight itself walks through is refused — the planner picks each course's direction
  independently, so a flight that folds back on itself would otherwise seal its own staircase from
  underneath.

  Measured on the final tree, three pinned rehearsals (`-PshaftColumn=-8,19` ×1, `-9,21` ×2):
  **12 `buildTo` calls, 0 refused, 8 landed on the exact row and column — 23% → 67%.** The
  top-two-row landings the archive had never once reached now get built:
  `wet.8.ramp.laid = 4/4 级垫好了`, `wet.8.ramp.rampedY = 60/60（同一柱）`, and the south geometry's
  PASS is the first in this rung's archive where the flight, rather than a floor-level spot, carried
  cells nine and ten (`frame.cast = 10/10`, `portal.cells = 6/6`, ten `recover*` CONSUME).
- **A flight's builder was standing on the flight.** With the shoulder in, the next thing to fail was
  the placement itself, and the row that reported it had been asserting a mechanism it never
  measured: `.step.N` printed 「六邻没有能贴的实心面」 unconditionally whenever the body held a
  cobblestone and the cell stayed air. The archive already contained its own refutation —
  `cell.6.ramp.step.1 = -8,57,37 垫不上（…六邻没有能贴的实心面？），身体 -8,57,37`, about a cell the
  body was **standing in**. Nine of the ten archived `.step.N` rows name a cell face-adjacent to the
  body at its own feet row.

  So the row now asks the world. It separates "no face at all" from "a face, and the body's own box
  in the cell", and on the first run that carried it the answer came back in one line:

  ```
  cell.6.ramp.step.1 = 6, 57, 18 垫不上（现在是 air，贴得到实心面（但身体自己的碰撞箱压在这一格里
                       —— vanilla 的 isUnobstructed 会拒，身体精确位置 6.60/57.00/17.78）），身体 6, 57, 17
  ```

  A player's box is 0.6 wide, so 0.28 off centre is enough, and the walker leaves a body wherever the
  last edge ended rather than in the middle of a cell. `approach` had been asked to keep the body out
  of the bottom step only; it now keeps it off the whole footprint — steps, shoulders, stands and
  head room — and `lay` steps aside to such a cell instead of climbing onto the course below when a
  cell it needs is blocked. One variable, measured against a baseline that had become deterministic
  (two runs, 8104 and 8096 ticks, the same cell and the same reason):
  `cell.6.ramp` went from `57/58，laid 1/2` to **`58/58（同一柱），laid 2/2`**, and the run carried
  cells 0–6 instead of dying on cell 7 — 8104 → 26748 ticks.

  *The rung's red has moved rather than gone, and the round's own bar was not met.* The east arm
  (`-PshaftColumn=-8,19`) is **0 PASS in 3**, so the ≥3 it was asked for is not close; what it now
  dies on is the walk back to the alcove — `cast6.returnStopped = 想到 -1, 60, 19，停在 -5, 64, 19`,
  after the body's own `returnStuck` pillar broke a stair that the mend then had to knock back out.
  The south geometry (`-9,21`) is **1 PASS in 2** against **1 PASS in 1** on the parent commit, which
  is too few runs either way to say whether it moved. Neither death is the flight.

  *Negative result worth not re-deriving, from the control run:* **on south the rung passed while the
  flight refused 7 of its 9 calls.** The cells it refused for — nine and ten, both ranks — were served
  from the alcove floor by `standToPour` / `fillFrom`, exactly as the one archived east PASS had
  served them. So "the flight refuses" was never by itself the thing that decided this rung, and the
  first read of the changed tree's south FAIL as a regression was wrong: the repeat passed. A refusal
  rate is a property of the flight, not a verdict on the rung.
- **Rung 12's opening walk had never once arrived, and a destination could not fix it — the ROUTE
  had to be priced.** The leg was `walkToColumn(lava.x, lava.z)`: the lake's own centre column, a
  cell no body can occupy. Every archived rehearsal that carries the end-reason row says so, **six
  runs of six**, and `ARRIVED_WITHIN` passed each wreck off as an arrival because it happened to
  stop inside five blocks of a goal it never reached:

  ```
  east FAIL 10608t  end=failed:no progress for 1200 ticks   停在 -13, 66, 21   ← pinned on the rim
  east FAIL   206t  end=failed:no path (expanded=1)         停在 -12, 63, 20   ← in the pool
  ```

  Both of that arm's upstream deaths are downstream of that one line. The first is the crater's lip:
  `[walker] footing guard: sole 0.0000 < 0.18 at -13,66,21 beside a lethal drop → sneak-pin`, after
  which vanilla refuses every horizontal move and the three legs of `stepOntoDiggableColumn` are
  three identical questions from one cell. The second needs no guard to explain it — `expanded=1` is
  a start node the pathfinder judges lethal, at the lava's own row — and that run died 206 ticks in
  with the back-off itself unable to move (`shaft.backOff.2 = -12, 63, 20（想退到 -16,24，只退到这里）`).

  *Negative result, measured, and the reason the fix is where it is:* **naming a bank cell instead
  of the pool is inert.** `JourneyTerrain.bankStandNear` picks the closest cell to the pool that is
  standable, dry and off the rim by `onThePoolsLip` (the loading station's own rule, moved here from
  `JourneyFill` so both callers share it) — a real reading, which vetoed 37 of 289 columns and
  answered `lava.bank = -8, 66, 19`. The leg then walked the body onto the rim anyway
  (`footing guard` at `-14,66,21`, `-13,66,20`, `-13,66,21`, three consecutive steps of one planned
  route) and **failed harder than before**: FAIL 3826t, `lava.goto.1/2/3 = end=failed:no progress
  for 1200 ticks`, because the new destination sits 5.39 blocks from the wreck instead of 4.47, so
  `ARRIVED_WITHIN` no longer hid it and `walkToColumn`'s wedge recovery took over — aiming at
  `lava.viaMidpoint = -10,20`, which is the pool. A destination cannot steer a path, and the
  midpoint remedy is wrong at a lake for the reason `stepOntoDiggableColumn` already had written
  down and `walkToColumn` never got.

  So the rim is priced instead. `JourneyTerrain.poolsLipCells` precomputes every cell around the
  pool that `onThePoolsLip` refuses, once, on the server thread, and the approach carries it as a
  `CostModifier` — 300 per rim cell against a plain walk edge's 10, so thirty blocks of detour is
  cheaper than one step onto the rim. A tax and not a `Constraint` on purpose: it leaves the route
  available when it is the only one. `Intent` has taken a bias list since it was written and nothing
  in this suite had ever passed one; `walkToColumn` now threads it onto the midpoint leg as well,
  because an unbiased recovery from a biased leg walks into exactly what the leg was told to avoid.

  Measured, one variable on top of the destination that had just been measured alone:

  ```
  lava.rimTax  = 613 格坑沿每踏一格加价 300（普通走一格是 10，即绕 30 格也比踏上去便宜）
  lava.gotoEnd.1 = end=arrived err=null（判为到达：停在 -8, 66, 19，距 -8,19 0 格，容差 5）
  lava.arrivedDistance = 0    lava.walkAttempts = 1    footing-guard lines on the leg: 0
  ```

  The first `end=arrived` in this rung's archive, in one attempt, from eleven searches.
- **The staircase's first step could be refused forever, and the row that said so could not say
  why.** With the approach fixed the body now arrives at the shaft column exactly, and the flight
  then wedged on its own first step in **two of three** runs: eighty legs of
  `stair.N = -8, 66, 19 → -7, 65, 19` / `stair.N.waited = -8, 66, 19 还没迈下去`, byte-identical,
  ending 「楼梯挖不到底：试了 80 级仍停在 -8, 66, 19」. One sentence, at least four worlds: the cells
  were never cut, the step has no floor, a route exists and the body cannot walk it, or the body is
  still falling. `stair.wedged` now prints the walker's own end reason beside the four cells that
  decide whether the step exists, once rather than eighty times, and it answered on the first run
  that carried it:

  ```
  stair.wedged = -8, 66, 19 连着 3 腿一格没挪（精确 -7.00/66.00/19.35）；想去 -7, 65, 19；
                 end=path-consumed err=null；台阶四格：脚下 -7, 64, 19=dirt，落脚 -7, 65, 19=air，
                 头 -7, 66, 19=air，起跳 -7, 67, 19=air；canBreak(落脚)=true，allowBreak=true
  ```

  The step exists, is cut, and is standable. The body is **half a block short of it and one row up**,
  at `x = -7.00` — the exact face of the block still holding it — and the walker calls that arrived
  and consumes the path: `wd.serverWalkerArrivedShort` in miniature, a goal one cell across and one
  down being swallowed by the walker's own arrival tolerance. So the remedy changes the question
  rather than the tolerance: a step already refused three times is cut **together with the one below
  it** and the walk aims at the second, two across and two down, which no tolerance can call reached
  from here. Both steps are still cut, so the flight the return legs walk is the same flight; the
  body simply does not stop on the first of them. Measured: `stair.0/1/2` refused,
  `stair.3 = -8, 66, 19 → -6, 64, 19（上一级被拒了三次，这一腿一次挖两级、直接瞄第二级）`, and the
  flight then reached `forge.landedY = 56`.
- **The carve left the body on the surface and the casting started from there.** Every other phase of
  rung 12 that can leave the alcove ends by walking back into it — `returnToTheForge` runs after
  every fetch trip — and the carve, which leaves it most reliably of all, did not. It leaves because
  the alcove's ceiling is two blocks under the grass, so for the handful of cells `breakItWhereItStands`
  cannot swing at, `MineProcess`'s cheapest route is up the staircase and down from outside;
  `carve.stuck`'s own key gives it away, measuring each cell against「the row the body's feet ended
  on」and reading `y=64` for an alcove whose floor is `y=56`. Three east runs, the same two rows and
  no others:

  ```
  FAIL 8055t / 10608t / 8169t   forge.carved=66/67   forge.swung=63..64/67   carve.stuck={-2=1}
                                cell.0.standMissed=想站 3, 56, 19，停在 2, 65, 19
  ```

  The cast's own walk cannot answer it — `walkToStand` gets 300 ticks and `NoBreak` to cross nine
  rows of rock it would have to go round by the stairs — so it reported a stand missed by 9.22
  blocks and the dig then reported `canBreak=false` at 12 m, three readings none of which names the
  body being outside. The transition now walks home through the same leg, with the same audit and
  the same pillar-out recovery, that the fetch trips have always used; a body still in the alcove
  returns from its first line without moving. Measured on the run that carried all three of this
  round's fixes: `forge.return = -8, 66, 19 → 楼梯口 … → 楼梯底 6, 56, 19`, which also mended a step
  on the way (`forge.stairsBroken = 1/16 级坏了`), then `forge.returnedY = 56` and
  `cast0.result = CONSUME`. That run cast and recovered **eight cells** and poured a ninth
  (`cast0..cast8 = CONSUME`, `recover0..recover7 = CONSUME`) before dying at `recover8` on a water
  fill — a failure this arm had never lived long enough to reach.
- **A loading station may no longer sit on the lava lake's lip.**
  `pinTheFillStation` ranked candidates by how many sources a bucket could see from them and said
  nothing about whether a body could stand there — which matters because the station is walked to
  **ten times**. On the `south` rehearsal geometry it chose `-14, 65, 21`, a cell in a notch whose
  east side is open air down to the lake, and two consecutive runs died on that one cell in two
  different ways:

  ```
  run A  FAIL 12595t  trip 7  06:39:49 search-begin start=-9, 66, 21 goal=-14,65,21
                              06:39:55 [walker] footing guard: sole 0.0938 at -12,66,21
                              06:39:58 search-begin start=-13, 62, 19          ← in the lake
                              …sank to -15,59,19; ascendByTowering cannot pillar out of lava
                              (climb.0 above=lava … stuck (no Y gain)) holding 128 cobblestone
  run B  FAIL 17410t  trip 8  lava8.aimsAt … 眼睛 -13.70/63.62/22.84           ← filled from y=62
                              cast8.returnStuck3#1.gained = 4/4                ← pillared back out
                              cast8.returnStopped 停在 -14, 66, 21 …脚下 air    ← and wedged there
  ```

  **Read the distribution off ONE run, not off the pair**: run A's log carries eleven
  `[walker] footing guard … at -12/-13/-14,66,21 beside a lethal drop` lines and *two* falls — the
  sixth trip fell as well and happened to land on the lava's own surface at `y=63`, where it could
  climb out. A stand the rung visits ten times and falls off twice is not a stand that was unlucky.
  Run B's death is the plainer statement of the same fact: the body ends up in the cell **above** the
  station with nothing under its feet, `soleOnSolid` at zero, and vanilla's `maybeBackOffFromEdge`
  then shrinks every horizontal move to nothing — pinned on the doorstep of the stand it was pinned
  to.

  So a candidate is refused when the lake is one sideways step from it: `onThePoolsLip` is the
  hazard half of the walker's own `lethalDropAdjacent` (any of the eight horizontal neighbours whose
  foot cell and the cell below it are both open, whose column then falls to lava within eight),
  asked at the cell **and at the cell above it** — because the body arrives there first and run B
  never got any further. A preference and not a rule, the two-pass shape `standToFill` already uses,
  so a bank with no clear stand is no worse off than before; the `station` row now names which pass
  answered. `STATION_REACH` goes 5 → 8 in the same breath and only in company with it: at five,
  every candidate that could see a source on this geometry was on the lip, so the rule would have
  had nowhere to go.

  Measured, one variable, `south` rehearsal: `station = -17, 65, 15：够得着 2 格源块，距楼梯口 10 格；
  脚边一步之内没有通向岩浆的空洞（严格判据）；否决计数 {…脚边就是通向岩浆的空洞=4…}`, then
  `forge.carved = 67/67`, ten `recover*.result = CONSUME`, `frame.cast = 10/10`,
  `frame.obsidian = 10/10`, `portal.cells = 6/6`, **PASS 18608t**, `staging.calls = 12`. Ten trips,
  no fall, no `returnStuck`.

  Two things the run says that are worth not re-deriving. **`stationSees` is not a budget**: it read
  `1` on trip 1 and `0` from trip 2 onward and all ten fills still took, because `visibleSourceNear`
  re-asks from wherever the body actually stands and `clearedLine` opens new sightlines — the same
  way the archived `east` PASS finished ten casts from a station that never saw more than one. And
  **the approach is still expensive**: 117 `search-begin … goal=-17,65,15` lines and 47 footing-guard
  pins, because the route to any station on this seed crosses the crater's rim whatever the
  destination. That is a cost, not a failure, and it is why the run takes 18 608 ticks.

  *Negative result, so it is not re-tried:* refusing the **route** rather than the destination was
  implemented first and measured **inert**. A check for lava under the straight line from the
  stairwell mouth (`lavaUnderTheWalk`, scanning down from the interpolated row to the first solid
  cell) turned away four candidates and kept `-14, 65, 21` — the run-B station, whose walk really
  does have `(-12,63,21)` under it in the archived world but not in the pinned one. The lake there is
  not under the walk, it is beside the destination. Reverted; the reading that works is the
  destination's own neighbourhood.

  *And the `east` regression arm is NOT green, on causes upstream of all of this.* Counting every
  archived run of `-PshaftColumn=-8,19`, it is **one green in five**, and the one green is what the
  last handoff called that arm's proof: `FAIL 8055t`, `FAIL 5001t`, `PASS 19807t`, then on this tree
  `FAIL 10608t` and `FAIL 206t`. Both new reds land before the station is ever walked to —
  `forge.carved = 66/67 格开了，1 格没挖动` with `cell.0.standMissed 想站 3, 56, 19，停在 2, 65, 19`,
  and `lava.gotoEnd.1 = end=failed:no path (expanded=1)（判为到达：停在 -12, 63, 20）` with
  `forge.surfaceY = 64（脚下 y=63）`, i.e. the body standing **in** the lake at the lava row after the
  rung's own opening walk. So they say nothing about the station rule, and the station rule cannot
  have caused them: `pinTheFillStation` runs inside `castTheFrame`, after the carve. What they do say
  is that this arm's red was promoted to「假红」on a single green — the same one-sample mistake the
  archive already charges twice — and that the crater lip kills rung 12 in at least three more places
  than the station: the opening walk to the lava, the step onto the dig column (`shaft.backOff.N` now
  observed *failing to move a body that is already in the pool*: `想退到 -16,24，只退到这里`), and the
  carve's own reach.
- **An approach to a dig column no longer re-asks a question the body cannot answer.**
  `stepOntoDiggableColumn` had no wedge handling at all — `walkToColumn` beside it has carried some
  since the iron rung issued ninety searches from one cell — so its three attempts were three
  identical 1 200-tick legs from the same cell. Measured on the `PORTAL_LIT` rehearsal of 2026-08-17
  with the shaft column pinned to the climb's own `-8,19`: three `shaft.stepping.N` rows all reading
  `-13, 66, 21 → -8,19`, 3 600 ticks, and about 110 `[pathfinder] search-begin owner=goto
  start=-13, 66, 21` lines. **The searches were not failing.** They arrived 1.4 s apart, which is
  exactly the cadence of `Walker`'s own `guardPinStreak >= 30 → path = null`: a route was found
  ~150 times over. What the body could not do was walk. `[walker] footing guard: sole 0.0000 < 0.18
  at -13,66,21 beside a lethal drop → sneak-pin` had it held on the lip of the lava lake's crater —
  read out of the archived world save, `-13,65,21` and `-14,65,21` are open air over a cave, with
  the lake's lava two rows under the neighbouring cells — and vanilla's sneak refuses every
  horizontal move that would keep a body off its floor. So the remedy is not more attempts, and the
  direction is the whole of it: `walkToColumn`'s midpoint answer is wrong here, because the midpoint
  of a body on the crater's lip and a column on the far rim is the pool. A leg that moves less than
  `WEDGED_UNDER` now backs the body four blocks AWAY from the pool and re-asks from there
  (`shaft.wedged.N`, `shaft.backOff.N`). Nothing is relaxed — the column asked for does not change,
  and a leg that moves is untouched.

  This unblocked the whole rung on the climb's own geometry, one variable changed:
  `shaft.wedged.2 = -13, 66, 21 这一腿一格没挪`, `shaft.backOff.2 = -17, 66, 24（退到了）`, then
  `shaft.standingOn = -8,19 (选定柱)` and `stairs.top = -8, 66, 19 往 east 下 10 级`. The rehearsal
  then **reached `PORTAL_LIT`** — `forge.face = 4, 56, 19 朝 east`, `forge.carved = 67/67 格全开`,
  `carve.stuck = 无`, ten `recover*.result = CONSUME`, `frame.cast = 10/10`,
  `frame.obsidian = 10/10`, `portal.cells = 6/6`, PASS in 19 807 ticks with `staging.calls = 13`.
  **So the `east` mould's standing red was a shortfall of scenery, not a defect.** With the geometry,
  the cobblestone (111) and the bucket (empty) all matched to the climb's measured values, and an
  approach that actually arrives, the carve that used to stop at 66/67 with the body up on the grass
  finishes. Two rules for any rehearsal follow and are worth stating once: **give what the climb was
  measured carrying at that rung, not a convenient round number**, and **hand tools over in the state
  the climb reaches them in** — a full bucket skips the walk that decides where the body stands when
  the next phase starts.

  The `south` regression arm is RED after this and **not because of it**: that run recorded one
  `shaft.stepping.1` and no `shaft.wedged.*` at all, so the branch never ran and its code path is
  byte-identical to before. It carved 67/67 and then lost the body inside the lake at cast seven
  (`走不回模腔：停在 -15, 59, 19 … 身处 lava 头顶 lava`) from a loading station, `-14, 65, 21`, that
  sits on the crater lip — the same lip, named by `sole 0.1798 < 0.18 at -14,66,21` in the same log.
  The only behaviour still on the tree that differs from that arm's last archived PASS is the empty
  bucket, and one run is not a distribution; both are written down rather than assumed.
- **`walkToColumn` records the walker's own verdict on the ARRIVAL path too.**
  `arrivedDistance=4, walkAttempts=1` is the same two digits for two different worlds: a body that
  walked here and stopped inside the tolerance, and a body the walker gave up on four blocks out.
  The end reason existed the whole time and was written only on the not-arrived branch, so the
  `PORTAL_LIT` rehearsal of 2026-08-17 filed a textbook-looking arrival for a body that had been
  sneak-pinned on a lava crater's lip for 1 200 ticks, and the rung downstream spent every remaining
  tick asking it to walk five more blocks. It now prints either way:
  `lava.gotoEnd.1 = end=failed:no progress for 1200 ticks (best dist=44) …（判为到达：停在
  -13, 66, 21，距 -9,19 4 格，容差 5）`. `ARRIVED_WITHIN` is deliberately unchanged — five blocks of
  slack is right for a caller whose next step is a search — but a caller whose next step needs an
  exact column can now see which kind of arrival it was handed.
- **A nether crossing now judges a hop by how much closer to the goal it got, not by how far the
  body moved.** The hop loop counted a hop as progress whenever its DISPLACEMENT was four blocks or
  more, so the ladder it escalates through — halve the reach, then aim 60° off the straight line —
  only ever fired for a body standing still. A body walking in circles displaces plenty. Measured on
  the `BLAZE_ROD` rung at seed 5471: hops 4–24 of the fortress crossing shuttled between `(87,77)`
  and `(110,116)`, every hop displacing 40+ blocks and passing the test, 21 hops and 18 339 ticks for
  **five blocks** of net progress, and the escalation ladder was not used once.

  This is the second time the same quantity has hidden a wedge here — the first was a whole ATTEMPT
  whose 69 blocks of displacement concealed 1203 ticks of standing still at its end — so both cases
  are now written side by side on the constant that decides it (`PROGRESS_UNDER`).

  The replacement is a **ratchet**: a hop counts only when it gets the crossing closer than the
  crossing has ever been. Plain per-hop net progress is not enough and the archive says so — replayed
  over those 24 recorded hops, the shuttle's gains alternate −48, +41, −49, +47, so a consecutive
  counter resets every second hop and never fires either; only the ratchet does, at hop 11. A
  shuttle is precisely a sequence whose per-hop gains cancel one hop apart, so any criterion with one
  hop of memory is blind to it. The record is not lowered by a hop that gains less than the bar, so
  small gains accumulate instead of each being re-owed; only walking backwards earns nothing. Two
  readings the shuttle was invisible without now ride on every hop line (`纪录`, `净进`) and on the
  crossing summary (`全程最近`), and the give-up message no longer says "一格没挪" about a body that
  may have walked 200 blocks.
- **A driven body now tells the `ChunkMap` it moved, so the level will spawn mobs where it is.**
  A real player's movement arrives as a packet, and `ServerGamePacketListenerImpl.handleMovePlayer`
  ends in `getChunkSource().move(player)`. A body driven by `ServerPlayerAvatar.step()` sends no
  packets, so for a body that JOINED the server (`-Dworlddriver.realPlayerBodies=true`) the chunk
  map kept the section it was PLACED at, for the whole run. Three things read that stale section
  rather than the body's position — its chunk tickets, its entity tracking, and
  `DistanceManager.hasPlayersNearby`, which is the gate `ServerChunkCache.tickChunks` puts in front
  of `NaturalSpawner.spawnForChunk`. That gate is a fixed 8-chunk window, so a body that walked more
  than ~128 blocks from where it joined walked out of the only place the level would spawn anything,
  and nothing said so: mobs kept spawning, back where it came from. Measured on the ladder's nether
  rungs — 107 monsters within 128 blocks while the body was still beside its portal, 2 after it had
  walked to a fortress 360 blocks away, and 0 for 7200 ticks in a warped forest, a biome whose
  monster list is endermen and nothing else. `step()` now calls `move` for a body that is in
  `ServerLevel.players()`; the guard is load-bearing, not defensive, because `ChunkMap.move` ends in
  `DistanceManager.removePlayer`, which dereferences the `playersPerChunk` entry for the section
  being left and a never-placed body has none. A/B on one rehearsal of the `ENDER_PEARL` rung, same
  seed and same start: `ChunkMap 认为这一格近旁有 0 个玩家（它记的身体在区块 [0,0]，差 15 区块）`
  became `1 个玩家（差 0 区块）`, and the monsters within 128 blocks of the body went from 66 —
  spawned in the sliver where the stale window still overlapped the live 128-block disc — to a
  saturated 106–109. **Backtested, not live**: the rung this was for has not once executed under it
  on a real ladder, and the two ladder runs that carried it stopped at rungs 11 and 12 on older
  walking failures. Those two stops were chased rather than assumed, and neither is this change's:
  every rung of both runs ran at 20.00 ticks/s including the two that failed, which is not what a
  chunk-churn cost looks like; the body joins at chunk (3,3) — logged, not assumed — and rungs 2–12
  never work further than 5 chunks from the spawn chunk, so their whole working set sits inside both
  the old spawn-pinned ticket bubble and the new body-following one, and the loaded/ticking state of
  every cell they touch is identical either way; and rung 12 re-run alone as `-Prehearse=PORTAL_LIT`
  under this change is a row-for-row match with the archived pre-change control
  (`frame.cast=10/10`, `portal.cells=6/6`, ten `recover*.result=CONSUME`). See `TODO.md`.

### Changed
- **The enderman rung walks at a warped forest's INTERIOR, not at its nearest edge.** It used to ask
  `findClosestBiome3d`, which by construction returns a cell on the biome's boundary, and walk there
  with an 8-block tolerance — so "walked to it" and "standing in it" were never the same claim. The
  run that carried it out arrived and reported `warped.arrivedBiome = minecraft:nether_wastes（停在
  130, 41, -229）` against a sample point at `136, 41, -233`. What replaces it is the quantity vanilla
  actually consults: a spawn attempt picks a random cell in a chunk with its y drawn uniformly from
  the floor to the surface (`NaturalSpawner.getRandomPosWithin`) and reads the biome AT THAT CELL to
  choose the mob list, so the deciding number is not which biome is under the boots but what share of
  the spawnable VOLUME nearby is warped forest — a biome whose monster list is endermen and nothing
  else, against `nether_wastes` where an enderman is weight 1 against a zombified piglin's 100. A new
  `WarpedGrid` samples that share off `getUncachedNoiseBiome` (no chunk loads; a 432-block survey
  measured 9 ms) on a 16-block grid, eight heights per column, and the walk aims at the column where
  the share within the hunt's own 48-block radius is highest. Both shares are then printed from
  wherever the body ends up — 48 blocks because that is what the hunt can see, 128 because that is the
  window whose biomes decide who fills the level's shared 70-monster cap. Measured at seed 5471 from
  the nether entry at `8, 41, 7`: the nearest warped column is 272 blocks out, and the densest is at
  `168, ?, -281`, 329 blocks out, where the 48-block share is **100%** against **0%** where the body
  stands. **Backtested, not live**: no run has yet stood in that forest — the crossing to it fell into
  lava on its first hop, which is the crossing's own long-standing account and not this change's.
- **The survey radius is 384 rather than 256, because 256 could not see this seed's only forest.**
  The 256-block square around the nether entry contains no warped column at all; the nearest is at
  272. At the old radius this rung could only ever report "hunt where you stand", which is what every
  run of it did. `MAX_HOPS × NETHER_HOP` is 1152 blocks of reach and the crossing has been watched
  carry a body 272 blocks to that forest once, so the binding constraint was the survey, not the legs.

- **A survey that found nothing worth walking to no longer reports finding nothing.** The first
  version of the row above printed "not one warped column was sampled within 256" whenever no column
  QUALIFIED as a target — and the first run carrying it printed exactly that over a survey that had
  sampled this seed's warped forest, 272 blocks out at the rim, outside the candidate margin. Those
  are different worlds with opposite next moves (widen the radius / change the plan), and the row
  could not tell them apart. The grid now samples `candidateRadius + neighbourhoodRadius` and ranks
  only the inner region, so no candidate is scored on a truncated neighbourhood and none is silently
  excluded; the miss reports how far the nearest sampled warped column actually was.

### Fixed
- **The nether census named the wrong bound for its own blind spot.** It claimed the 128-block count
  was limited by the rung's 4-chunk pin ("= 64 格"). That pin is a FLOOR, not a limit: a body that
  joined the server also holds its view-distance tickets, and the same run whose census quoted a
  64-block horizon counted 106 monsters inside 128. A reader who believed the row would have gone
  looking for a truncated count instead of a full one. It now asks whether the chunk on the census's
  own rim is loaded and prints the answer — measured `装着` on the run that followed.

### Added
- **A census of zero now says WHICH spawn gate is shut.** `JourneyNetherRungs.spawnGate`, appended
  to every `census(...)` the two nether rungs print, asks `ServerChunkCache.tickChunks`'s own three
  conditions in its order: `level.isNaturalSpawningAllowed(chunk)` (the chunk is entity-ticking),
  `chunkMap.getPlayersCloseForSpawning(chunk)` (the public twin of `anyPlayerCloseEnoughForSpawning`),
  and the category cap with its arithmetic spelled out (`70 × spawnableChunks / 289`). Beside the
  second it prints the chunk the `ChunkMap` has on file for the body against the chunk the body is
  standing in, because that drift is the only one of the three inputs a WALK can break by itself.
  Before it, `enderman.found=0/6` was the one sentence four different worlds printed — nothing
  spawns, the wrong biome, the cap is full elsewhere, they spawned outside the search radius — and
  the biome row beside it could only speak for one of them. The first run carrying it separated
  them in one line.
- **A fall now says what the body was standing on when it left the ground.** `JourneyFlight` records,
  for the tick before each fall, the sole's actual contact area with solid ground (enumerated the way
  vanilla's collision does — outward by 1e-7, not the old footprint's inward 1e-4, which discards
  exactly the sliver a body walking off a ledge is held by), vanilla's own "is there anything within
  0.0784 below me" question asked again, the vertical velocity that separates a JUMP from a walk-off,
  a 5×5 map of the support row marking which open cells end in lava, and the walker's lethal-edge
  predicate recomputed off the level. Three rounds of this crossing had each guessed at one of those;
  the first run carrying it split four falls into three different mechanisms in one reading.
- **`BotState.ProcessSlot.driveTag` / `jumpTag`.** `Walker` already kept both as telemetry and nothing
  outside it could read them — the journey's crossing runs its own `IntentProcess` walker, not the
  driver's. `driveTag` is null when a tick returned early from `tickInner`, which is what separates
  "the edge guard said no" from "the edge guard never ran"; one nether fall was measured to be the
  second, while the crossing was digging its way along.
- **A walk now says WHICH CELL it is steering at, not only how far along the plan it is.**
  `BotState.ProcessSlot` gained `pathNode` / `pathMove` beside `pathLen` / `pathStep`, published by
  `IntentProcess` from the same tick, and `Walker.pathMove()` names the edge that enters the node
  (`walk`, `stepDown`, `fall4`, `parkour3`, …). Every earlier reading of a body that left the ground
  was about the cell under its FEET, and two opposite failures write identical feet: a next node
  genuinely across a gap (the planner is at fault) and a fine node the body slid past (the executor
  is). The journey's `JourneyFlight` snapshots it at the launch tick — not at the landing tick, which
  is a different plan — and it named the nether crossing's killer on the first run that carried it:
  `计划下一格 16, 53, 22[parkour3] … 距身体 2.90 格` followed by `落进岩浆 14, 29, 23，坠 24 格`.
  Deliberately not in `snapshot()`: it is a debugging reading for in-process consumers, and every
  `mc.bot.status` poll ships every slot to an LLM client.
- **`JourneyFlight` counts the ticks a leg had no plan at all.** The reading that separates "the
  nether is hard terrain" from "the walker never answered": the crossing's second and third attempts
  spent `1203/1582` and `1203/1203` ticks with nothing to steer at, and the server log carries 2402
  `search-begin` lines from the single cell `66,43,67` — one full A* budget per tick for two minutes.
  Nothing in the old evidence could distinguish that from a slow walk.

### Changed
- **A `PORTAL_LIT` rehearsal hands the bucket over EMPTY, like a climb's.** It used to hand the water
  already in it, reasoning that rung 12's walk to water re-tests a rung-10 capability at rung 12's
  expense. That is sound about cost and wrong about fidelity: the climb fills its own bucket here
  (`waterFill.hand = minecraft:bucket`, `waterFill.result = CONSUME`), and **where that trip leaves
  the body is what decides where it is standing when the carve begins** — the exact quantity under
  investigation, since the rehearsal's carve fails with the body up on the surface while the climb's
  on identical geometry carved 67/67. A staging shortcut may skip a walk; it may not skip a walk that
  decides the thing being measured. Measured: the fill itself works (`water_bucket = 1`,
  `waterFill.cellAfter = air`) and the trip does move the return point — `lava.arrivedDistance` went
  from 1 to 4 — which is the confirmation that this variable was worth aligning. That same run was
  then **blocked upstream and measured nothing about the carve**: from four blocks out the body could
  not reach the pinned column within `MAX_WALK_ATTEMPTS` (`站不到可下挖的柱子上：想去 -8,19，停在
  -13, 66, 21`), because `-PshaftColumn` deliberately refuses to adopt any other column. The two
  staging levers now interact, and that is the next thing to resolve; a rehearsal that stops there has
  learnt nothing about digging and must not be read as if it had.
- **A `PORTAL_LIT` rehearsal hands over the climb's measured cobblestone, not a convenient stack.**
  It was 64 because a stack is easy to type; the real ladder of 2026-08-16 reached this rung holding
  **111** (`cobblestone.before = 111`). The gap matters because a rehearsal stages the preconditions
  while a climb arrives carrying eleven rungs of residue, and inventory is the commonest thing left
  out of that sentence — this repo has already been fooled by it once, when a rehearsal carried
  cobblestone and a climb carried dirt, `tidyTheAlcove` matched only `Blocks.COBBLESTONE`, and the dig
  sealed its own foothold. `rehearsal.gave` now prints the figure alongside the climb's row it was
  matched to, and names the two differences that remain deliberate (the bucket is handed over full;
  there are two pickaxes) rather than leaving them to be rediscovered. **Measured on the change
  itself, with the shaft column pinned to the climb's own `-8,19` so the geometry is identical: it
  makes no difference to the failure under investigation** — `forge.carved = 66/67`, the same stuck
  cell `2,62,17`, the same `cell.0` unopened, the same 8055 ticks, on both 64 and 111. So the
  cobblestone is exonerated; recorded because an exoneration nobody wrote down gets re-tested.

### Fixed
- **A rehearsal can pin the shaft COLUMN, which is what reproduces a ladder's mould.** Staging a side
  can turn a mould but cannot reproduce one, and the reason is arithmetic: the climb of 2026-08-16 cut
  its `east` mould from the column `-8,19`, which is `dx=+1, dz=0` from the pool — **r=1** — while
  `pickDigColumn` rings outward from **r=2**. The column a ladder actually used is one that search can
  never propose; the climb reached it by `stepOntoDiggableColumn` adopting whatever it was standing on
  after `walkToColumn(lava)` stopped one block out (`lava.arrivedDistance = 1`). Asking for the east
  side instead returns `-7,17`, the nearest east column on the r≥2 ring, and a geometry the ladder
  never visits. `-PshaftColumn=x,z` pins it: `pickDigColumn` is bypassed and the adopt short-circuit
  accepts only that column. Verified — `-PshaftColumn=-8,19` reproduces the climb's mould to the
  block: same pool (`lavaLake -9,63,19`, 72 sources), `forge.away = east`,
  `forge.face = 4,56,19 朝 east`. That geometry had until now appeared only by luck, roughly one climb
  in three, twenty-seven minutes a draw.

  This also corrects a wrong inference recorded here earlier: the climb's `east` mould did **not**
  come from a second pool. The disproof was in the same evidence map, one line from the reading that
  prompted it — both runs name `lavaLake -9, 63, 19（勘测到 72 格源块）`.
- **A rehearsal's staged forge orientation now actually turns the mould.** `-PforgeAway=<side>`
  stood the body on that side of the lava lake and its evidence row promised
  「楼梯与模腔都会朝这边」. It could not keep that promise, and four directed rehearsals on
  2026-08-16 disproved it four times for four: `east`, `south`, `west` and `north` staged four
  different `rehearsal.stand` values and every one of them came back
  `shaft.standingOn = -9,21` with `forge.away = south`. The reason is structural — rung 12 opens with
  `walkToColumn(lava)`, which discards the staged stand, and `JourneyTerrain.pickDigColumn` then rings
  outward from the pool in a fixed scan order and returns the first qualifying column, which for a
  given pool is the same column every run. (The real ladder's mould varies only because rung 11 spends
  a pool, so rung 12 gets a different one.) So the side is now applied where the orientation is
  actually decided: `pickDigColumn` takes an optional preferred side, keeps only columns whose
  `awayFrom` matches — the rung's own rule, not a second copy of it — and falls through to the
  unrestricted scan, counting why, when that side offers none. A null preference iterates exactly as
  before, and the ladder's own `recon` clears the static, so a climb is unaffected by construction
  rather than by which gradle task ran. This is what makes "one directed six-minute run per
  orientation" a real substitute for "climb the ladder ten times and hope"; without it the lever had
  been staging nothing but the walk to the pool since it was written.

### Changed
- **The portal rung's water recover now requires the row its column was verified for, not merely
  "high enough".** `raiseColumn` verifies a scoop column by putting the eye at exactly `wantY`
  (`scoopSeesFrom`'s eye is `foot.getY() + eyeHeight`, and `foot` is on that row), but the walk to
  that column is a `Goal.XZ`, which has no opinion about the row at all — and `JourneyRamp.buildTo`
  then returned immediately on `here.getY() >= landing.getY()`. So a walk that delivered the body one
  row high skipped the flight entirely, the landing never got its floor, and the scoop fired a line
  nobody had checked. Measured on the real ladder of 2026-08-16, cell six of an `east` mould:
  `recover6.rise.raiseTo.arrivedDistance = 0` (the column was hit exactly),
  `recover6.rise.raisedY = 59/58`, and **no `.ramp.*` row exists in that run at all**. From one row
  up, the line into the water at `4,59,19` enters the frame cell `4,60,19`; `JourneyFill` rightly
  refuses to break a frame, so the run reported `frameStuck` and the frame took the blame for a row
  the body should not have been on. The landing was free and merely floorless — `standToFill` vetoed
  13 candidates as `脚下不实心` against 17 as `落脚格被占` — which is exactly the work the flight
  exists to do. `buildTo` gains an `exactRow` flag: pours keep `>=` (a pour aims at a backing and is
  genuinely served from any row high enough), scoops require equality. `raisedY` now names which side
  of the row it ended on, because one row high and one row low read alike and want opposite repairs.
  **No widening of the frame rule**, deliberately: this rung has three times shown that tolerating an
  upstream error just moves the failure. **`compiled`, plus one `-Prehearse=PORTAL_LIT` with no
  regression on a `south` mould (10/10 cast, 6/6 cells, ten `recover*` CONSUME)** — that rehearsal's
  two `.rise` rows were both the *too low* kind, so the new branch has not yet executed anywhere, and
  `east` moulds are drawn at random.

  **Now `backtested`, and the honest reading is that the branch runs and does not yet do what it
  claims.** The rehearsal of 2026-08-17 reproduced the climb's `east` mould and reached cell six, so
  the branch executed for the first time: `recover6.rise = … 高度已经够了 —— 差的是柱`,
  `recover6.rise.raiseTo.arrivedDistance = 0`, and the flight the old `>=` skipped now exists —
  `recover6.rise.ramp.flight = 2 级：3, 56, 20 → 3, 57, 19`, `ramp.laid = 2/2 级垫好了`. But
  `ramp.rampedY = 59/58` and `raisedY = 59/58（停在 3,20，指定柱 3,19，不是同一柱 … 比要站的排高
  1 排）`: the ramp built its steps and the body still ended one row high and one column over, so the
  scoop was NOT fired from the row its column was verified for. What saved the cell is the fill's own
  re-choice — `recover6.fromHere = 3, 59, 20 已经看得见源块 4, 59, 19（够得着）` → `CONSUME` — which
  is the third time on record that `raiseColumn`'s answer is a hint rather than a contract. So of the
  five things this change was to be judged on, four hold (`.ramp.*` present, ten `recover*` CONSUME,
  `frame.cast = 10/10`, `portal.cells = 6/6`) and the fifth — the raise landing on the verified row —
  does not. The rung is green over it, which is why it is written down.
- **The portal rung's water recover no longer accepts a height as an answer about a sightline.**
  `riseToTakeItBack` held two gates: the `SOURCE_ONLY` clip the bucket runs, and
  `if (here.getY() >= wantY) return;`. The second is what lost the real ladder of 2026-08-16 on its
  sixth cell — and the archived results file dates it without another run, because both gates were
  silent and only one of them can have fired. `recover0..5` each printed `.fromHere`, the row
  `JourneyFill.fillFrom` prints when the identical clip finds a source in reach; `recover6` printed
  `.spot` instead, the not-in-reach branch, from a call made in the same tick through `then.run()`
  with nothing in between that could move the body. So the clip had already answered *null* and the
  raise was skipped on height alone; no `.rise` row exists anywhere in that run. What the height gate
  could not see is that the body was in the wrong COLUMN: cell six casts `4,59,18` and its water sits
  beside it at `4,59,19`, the pour left the body at `3,58,18` — `wantY` exactly, one column north —
  and from there the line is a diagonal that has to squeeze past the cell the cast had just turned to
  obsidian (`recover6.aimsAt = 4,59,18 Block{minecraft:obsidian} 源块=false（想瞄 4,59,19）`, with
  `standToFill` refuting that same cell from its centre as `射线停在 Block{minecraft:obsidian}=1`).
  The raise now runs for its column whether or not the row is already right, and `.rise` names which
  of the two states it is in rather than describing only the shorter one.

  **Backtested on 2026-08-16, and the verdict is: the branch executes and is not enough to save an
  `east` mould.** Every earlier run drew the `-9,56,38 朝 south` geometry, which does not produce the
  wrong-column state at all, so this branch had never once run. The ladder of 2026-08-16 drew
  `forge.face = 4,56,19 朝 east` and it did: `recover6.rise = 3,58,18 看不见 4,59,19 里的水（脚在
  y=58，要站的排 y=58，高度已经够了 —— 差的是柱）`, followed by `recover6.rise.raisedY = 59/58（停在
  3,19，指定柱 3,19，同一柱）` — it moved to the right column and rose. The cell was still not
  collected, on a **new** cause the height gate had been hiding: from the right column the body now
  stands one row too HIGH, eyes at `y=61.29` looking down at `pitch=65.78` into water at `y=59`, and
  the frame cell `4,60,19` (dirt) sits on that line — `recover6.frameOnLine.3`, and the frame is not
  allowed to be broken, so `recover6.frameStuck.3 = 门框挡着 4,59,19，而且没有别的落脚点看得见它`.
  So `east` moulds have now lost the portal rung on both sides of this change. Not `live`.
- **A body grounded on almost nothing beside a lethal drop is now pinned, whatever branch actuated
  the tick.** New `Walker.footingGuard`, in the single-exit wrapper beside `strideFloorGuard`: sole on
  solid under `FOOTING_MIN` (0.18 of 0.36) plus a lethal drop in the eight neighbours holds vanilla
  sneak, cancels the jump and drops sprint. The lethal-edge gate in the drive could not do this job
  for two measured reasons — it probes only FORWARD (on the tick before an eleven-block drop into
  lava, `gapAhead` was false because the cell toward the waypoint was netherrack, `offCentre` was
  0.17, and the sole was on 0.0000 of 0.36), and it lives in the drive tail that dozens of branches
  return before reaching. Planned descents are exempt, and that is not a nicety: without it the suite
  reported `wd.descent` crouch-deadlocked, `wd.bridgeDescend` wedged and `wd.descentYaw` thrashing to
  2463°, all in one run.
- **The nether rungs price the diagonal ascent out of their routes.** `pathfinderDiagAscendPenalty` is
  set to 100 for the two nether rungs (restored by `generousPathfinding`'s pin, so the six gates never
  see it). A `diagUp` launches ACROSS the open corner between two shelves and its cost says nothing
  about what is under that corner; unlike a cardinal `stepUp` it has no alignment gate before it
  jumps. Measured: the crossing's reproducing fall was a `diagUp` jump from a cell holding 0.118 of
  one sole, with sneak already held. A price and not a ban — where the diagonal is the only way up it
  is still legal, merely dear.
- **The blaze rung stops counting corpses.** `blazesNear` filters `isAlive`. A killed mob stays in the
  world for its twenty-tick death animation, so every round after the first was handed the previous
  round's corpse and booked a kill in 0 ticks: one run reported `blaze.killed=8 只` with
  `rods.perKill=0,0,0,0,0,0,0,0` beside it, against a single real 54-tick fight. Eight kills and no
  drops reads as the `killed_by_player` gate; one kill and no drops is a blaze's ordinary 50/50.
  Re-measured with the filter in: seven fights of 52–57 ticks each — no more 0-tick ones — and two
  rods off seven kills, so that gate was never the problem.
- **The nether crossing walks in bounded hops instead of aiming at one distant goal.** A 397-block
  `Goal.XZ` is not a question this pathfinder answers; three attempts at it walked 15, 69 and 0
  blocks. The retry that was supposed to catch that measured the whole ATTEMPT — attempt 2 moved 69
  blocks and then stood still for 1203 ticks, so it passed the "did it move" test and attempt 3
  re-asked the identical question from the identical cell. A hop is short enough that "did this hop
  move" is the same question, and a wedged hop changes the question rather than repeating it: half
  the reach first, then 60° off the straight line. Measured on the same seed and start: 3072 ticks
  for ~110 blocks with a 2400-search wedge, against 1029 ticks for 103 blocks with `无计划` ticks of
  3, 2 and 12. Rung 15's walk to a warped forest is the same crossing and takes the same route.
- **The nether crossing does not leap.** Its hops carry a `CapabilityProfile` forbidding
  `Capability.PARKOUR`. A leap's cost does not include what is under the gap: over rock a missed
  `parkour3` costs a few hearts, over a lava chasm it costs the run, and the stride floor-guard is
  explicitly disarmed on a parkour tick because a leap's landing is supposed to be the plan.
  Measured — the crossing died fifteen blocks in on a `parkour3` whose landing cell was real ground.
  Bridging is untouched (`BridgePlace` is not a `PARKOUR` move and the rung arrives with 128 blocks),
  and the scope is the crossing only: an approach to a spawner three blocks away has no chasm to leap.
  **Not the general fix for a fall, and the record says so**: the run after it fell again on a plain
  `walk` edge to a cell 0.97 blocks away standing on netherrack. What all four measured falls share
  is not the move — it is `上一 tick 就已经没有支撑格了 … onGround 却还报 true`.
- **A pour now decides its aim with the ray that will fire it, and decides it after the settle it
  fires from.** Two separate splits between prediction and execution, both closed:

  `aimThatLandsIn` used to answer with a segment clip (`eye → block centre`, in doubles) while the
  bucket fires a ray re-derived from the float yaw/pitch `aimAtBlock` stored. Nominally the same
  line; not bit-for-bit. It now stores the angle and re-checks with the same `aimedAt` the `.picks`
  gate runs, tries the next candidate when they disagree, and records `.aimForked.N` when they do.

  And `placeFluid` aimed, then settled two ticks, then read `.picks` — the order `JourneyFill.scoop`
  was fixed out of on 2026-08-16 and the pour never was. Measured, single-bucket rehearsal
  2026-08-17 cell ten: `cast9.fromHere.3 = -9,57,36 就地瞄 -10,60,39，流体会落进 -10,60,38` and then
  `cast9.picks.3 = …身体 -9,56,36` — a whole block of eye height between the decision and the shot.
  Same geometry A/B: the run before poured nine cells and died on the tenth's ray gate; the run after
  poured **all ten** (`frame.cast=9/10（浇成过 10 格，浇成之后又丢了 1 格）`).
- **A pinned climb adopts the column it can actually reach, instead of stopping.** The refusal was
  audited before it was changed, and it prevented nothing on record: the pour's own `.picks` gate —
  "do not spend the bucket unless this ray lands in the target" — predates it by three days, so no
  bucket was ever spent from a drifted column; `raisedY` and `endedIn` already report the column, so
  the misdescribing row it was written against is impossible either way; and the guard that keeps a
  tower out of the mould is the unconditional drift correction, not the pin. What it did cost is
  measured: with the climbs finally tagged apart, one rung's six climbs read `3/3, 1/1, 2/2, 2/3,
  3/3` unpinned against `-1/2` pinned — the pinned one ended a block **below** where it started,
  because the correction descends into the column's only foothold and the refusal then forbids the
  tower that would have paid it back. Reproduced on the real ladder the same day
  (`recover8.rise#8.gained=-1/2`). An adopted column is now recorded as `driftKeptPinned`. The one
  refusal kept at the time was the column-blind `Goal.YLevel` fallback — see the next entry for the
  measurement that took that one too.
- **A pinned climb takes the walker fallback as well, without digging.** Keeping that last refusal
  rested on "it costs nothing to a climb that has a tower", and this rung's raise is the one climb in
  the ladder with no tower to fall back on: the recover runs while the cast's own source is still in
  the frame, so the alcove floor is flowing water and `TowerProcess`'s READY phase never sees
  `onGround`. Measured, single-bucket rehearsal, cell nine: `recover8.rise#3.climb.10.afloat =
  -11,56,36 …脚下 0 格内有实底（-11,55,36 granite），水深 1 格，身体 y=56.00`, then `pinnedShort`,
  then `gained=0/4` — and the rung died in the fill that followed
  (`recover8.frameStuck.3 = 门框挡着 -9,61,38，而且没有别的落脚点看得见它`). The **unpinned** lifts of
  the same run hit the identical puddle at the identical cell and got out of it on this very
  fallback (`cast6.lift#6` / `cast8.lift#7`: `afloat` → `walkerFallback=true` → `toY=58`,
  `gained` 2/2 and 2/3), so the fallback is not a worse way up here, it is the only one that works in
  water. By the time the refusal fires there is also no column left to defend: the correction has
  already adopted twice (`endedIn=-11,36（就是那一柱）` against an aim computed for `-9,36`).
  The fallback carries `NoBreak`, for the same reason the recover's fill leg does — the only thing
  tall enough to be in a walker's way down there is the frame the rung is building. Same geometry
  A/B on one bucket: before, `recover8.rise gained=0/4` and `装不到 minecraft:water_bucket`; after,
  `pinnedFallback=true`, `gained=4/4`, `recover8.result=CONSUME`, `frame.cast=10/10（丢了 0 格）`,
  `portal.cells=6/6`, three rehearsals out of three.

  On the real ladder, with no staging at all (`staging.calls=0`), that carried rung 12 to PASS and
  the body walked through its own portal: **the climb reached rung 13 (NETHER) for the first time**,
  which is what finally put rung 14 on the clock. It is not yet reliable — rung 12 is 1 of 2 on the
  ladder, and the run that failed did so in a cell whose lift never went near this code
  (`cast6.lift#5.climb.1.stalled = stuck (no Y gain in 60t — out of blocks?)` on dry ground,
  `onGround=true`, holding 124 cobblestone, with no `pinnedFallback` or `afloat` row anywhere in the
  run). See `TODO.md` for that one and for where the nether walk stops.
- **The water recover's walk may no longer mine.** Its source sits inside the frame the rung is
  building, so from floor level the only thing between the eye and it is the frame — and that walk
  ran with `allowBreak` on. Measured: `recover8.spot = 没找到能看见源块的落脚点，退回
  Near(-9,61,38,2)` and then `frame.lost.1 = -9,60,38 浇成黑曜石之后又没了：现在是 water，丢在
  「recover8 从 -9,61,38 收水」这一步里；身体 -9,59,38 距 1.0 格` — the body standing directly under
  the cell it had just broken, on a run where every one of the ten cells had cast. The lava fetch
  keeps its digging: it crosses open ground to a lake nowhere near the mould.

### Added
- **A climb that gives up afloat now says why it never landed.** `HoldStill` releases the inputs and
  nothing else, so gravity keeps running and eight sixty-tick legs are ample for a body to sink —
  which means「浮在水里，8 次都没落地」fitted three different worlds (no floor under the feet, water
  deep enough to buoy the body, or a body that is resting while `onGround` reads false) and could not
  pick between them. The row now carries the first solid floor under the feet, how deep the fluid
  reaches above it, and the body's sub-cell `y`. Its first run answered the question outright:
  `脚下 0 格内有实底（-11,55,36 granite），水深 1 格，身体 y=56.00，头 air 脚 water` — standing on
  rock, integer `y`, one block of water, and `onGround()` still false. The eight retries were eight
  retries against a condition that never changes.
- **Every climb records under its caller's name, and every pour approach under its own number.** The
  climb rows were bare `climb.<course>.*` / `exit.*` keys and one casting cell runs three climbs, so
  a ten-cell rung kept one `climb.0.driftInto` out of a dozen — and duly printed a self-contradicting
  pair from two different climbs. The pour had the same defect one level up: `.fromHere` from
  approach three sat beside `.stand` and `.picks` from approach one, which reads as "the
  short-circuit fired and the walk happened anyway". Keys are now `<caller>#<n>.climb.<course>.*` and
  `<tag>.<approach>`. The ordinal is not redundant with the caller — `liftInPlace` climbs twice under
  one tag.
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
- **Rung 12 builds a staircase inside its own mould, and the real ladder lit its first portal.**
  The alcove is a box cut out of rock, so every cell above its bottom row has air underneath, and
  three separate places in the rung had been saying so about the same geometry —
  `standBehind`'s `.noStand`, the pour's stand search and the fill's — all ending in
  「垫不了：… 撑不住 —— 一块砖会悬空」. One brick reaches the frame's bottom three rows and nothing
  above them.

  The tool that already existed for gaining height does not work here. Measured twice, verbatim, on
  two real ladder runs, with the body on dry land, standing, holding the blocks:

  ```
  cast6.lift#7.climb.1        = -9,57,36 above=air onGround=true water=false
  cast6.lift#7.climb.1.stalled= stuck (no Y gain in 60t — out of blocks?)
  cast6.lift#7.climb.1.stock  = minecraft:cobblestone ×130
  cast6.lift#7.gained         = 1/2 block(s)
  ```

  Sixty ticks, six jump-and-place cycles, stock unmoved. The one course that run did gain was the
  one the body spent IN WATER, where buoyancy lifts a body whether or not a block goes under it —
  which is also why the single-bucket rehearsal had been passing 10/10 over this the whole time: its
  alcove floods a cast earlier and the body floats a row higher than the ladder's does.

  So `JourneyRamp` plans a flight down from the landing over the corridor's own floor plan, lays each
  step by hand through `useItemOn` (the call `JourneyStairs.placeInto` already uses, measured working
  in this alcove), reads every placement back off the world, and the body WALKS up ordinary +1 steps
  — no jump, nothing that needs `onGround` to be trustworthy on this body. Every cell is checked
  before a block is spent: inside the corridor, feet and head clear, a solid face to click against
  (the descent flight cuts a notch through the back wall, so that is a real refusal, not a
  formality), never a cell of the descent flight, and never a fluid source — burying one loses the
  water bucket the next cell needs.

  The landing is chosen by the ray rather than by proximity. `liftInPlace` used to pillar straight up
  wherever the body was, which puts the eye in the BODY's column: the ladder of 2026-08-16 lifted in
  `x=-9` for a target in `x=-8` and the diagonal grazed the corner of the obsidian it had cast two
  rows below (`picks=-8,58,38 obsidian → 落进 -9,58,38`). It now prefers the cell one back and one
  down from the target — the only geometry that makes the backing shot horizontal — and falls back to
  `raiseColumn`. The tower is still run behind the flight, because it is what carries this rung
  through its own flood.

  `raiseColumn` additionally requires the landing to be a cell a body can occupy. It asked only
  whether the eye there would see the backing, which is true of a cell full of cobblestone, and by
  the ninth cast some of them are: the raise for the notch one row up rests its top step in exactly
  the cell the ring cell below it wants to stand in.

  `tidyTheAlcove`, `clearPourLine` and `litterAt` all skip the steps. Each exists to remove blocks
  that arrived by accident — the columns `MineProcess` pillars up — and a step is the opposite of
  that: it is the floor the next pour stands on.

  Real ladder, seed 5471: `rung.PORTAL_LIT = REACHED`, `frame.cast=10/10（丢 0 格）`,
  `portal.cells=6/6`, `journey.stagingCalls=0`. The two rehearsals that bracket it (control on the
  reverted tree, then this one) both read 10/10, so the rehearsal is not what this fixed — it never
  reproduced the failure.
- **The Nether rung walks to a portal cell it can stand in.** `nearestBlock` answers "nearest", and
  nearest is not standable: a portal block has no collision, so the floor of every cell but the
  lowest is another portal block. Which cell is nearest depends on where the previous rung left the
  body, and rung 12 now finishes three rows up on the staircase it built. Measured on the ladder run
  that first lit the portal: `portal.found=-9,58,36`, `stand.at=-9,58,35`, `stand.in=air`, then eight
  legs of「走进去」each ending in the same cell one block short — while the failure message blamed the
  teleport timer. The goal is now the bottom of the portal's column, whose floor is the frame's own
  obsidian row.
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
