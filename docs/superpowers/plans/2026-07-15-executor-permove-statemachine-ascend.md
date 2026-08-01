# Executor per-move state machine — ASCENT family first (task#82)

> **For agentic workers:** REQUIRED SUB-SKILL: `superpowers:subagent-driven-development`
> (recommended) or `superpowers:executing-plans` to implement this plan task-by-task.
> Steps use checkbox (`- [ ]`) syntax for tracking. Do RED→GREEN→commit in order.

**Goal:** Migrate ONLY the ascent/climb move-family (`stepUp`, `stairUpBreak`, `diagUp`) out of
the monolithic `Walker.tickInner` into a self-contained per-move state machine (`AscendMovement`)
whose `updateState(ctx)` OWNS its PREP→BREAK→ASCEND→CONFIRM sequence, its own input emission, and —
the crux — its own bounded timeout→cancel. This closes task#82: a no-tool bot that lands ~1.2–1.5 b
off a `stairUpBreak`/`stepUp` node sits in the horizontal step-advance dead-zone
(`cur2 ∈ (REACH_DIST_SQ=0.45, OVERSHOOT_RESYNC_SQ=4.0)`), no recovery gate covers the `+1`/no-`hCol`
case, and because A* reported `goalReached=true` the futile-search cap is blind, so the bot churns
forever. `AscendMovement` returns `UNREACHABLE` on a bounded timeout and folds into the existing
`fellOffPath` re-route instead of churning.

**Architecture:** A new `Movement` interface + `MovementStatus` enum + `MovementContext` bundle live
alongside `Walker` in `common/.../bot/movement/`. `Walker.tickInner` gains a SINGLE narrow delegation
branch (guarded by default-OFF `BotConfig.walkerAscendMovement`) inserted before the ascent
jump-timing block (`Walker.java:4287`); with the flag OFF the branch short-circuits and legacy
`tickInner` runs byte-identically. `UNREACHABLE`/`FAILED` set a new `forceFellOffPath` field ORed into
the existing `fellOffPath` recovery spine — the machine only supplies the *trigger* the dead-zone
never produced. The legacy ascent recovery gates stay resident (dormant behind the flag) until the
machine is proven live; only the never-validated default-OFF DELETE flags are removed, and only in
Unit 5 (gated on task#83).

**Tech Stack:** Java 21, Minecraft 1.21 multiloader (`common`/`neoforge`/`fabric`), NeoForge
GameTest (`@GameTestHolder` auto-discovery in `AgentGameTestTerrain.java`), `beginReplay`/`adoptForTest`
test seams, `PathArchiveRecorder` → `mc.debug.replay` live A/B tooling.

## Global Constraints

- **Incremental, ascent-only.** NOT touching any non-ascent move — walk, all descent/`fall`/`stepDown`,
  parkour, `pillarUp`, bridge/place, all water/swim moves fall through to legacy `tickInner`
  byte-identically. `stepUp2` (+2, horse/jump-boost) and `parkourAscend*` are NOT migrated (spec §10):
  the migrated set is exactly the three runtime move-NAMES `stepUp`, `stairUpBreak`, `diagUp`.
- **`walkerAscendMovement` default-OFF = byte-identical no-op.** With the flag OFF the delegation branch
  is provably skipped and NEVER constructs a `MovementContext`. `isMigratedAscent(String)` must be a
  cheap string check; the OFF path must not allocate. This is the zero-cost guarantee (spec §5, §8.3).
- **Migrate the KEEP set faithfully; delete the DELETE set only in Unit 5 (gated on task#83).** KEEP
  (spec §5): stepUp/diagUp jump-timing (`Walker.java:4291–4337`), `chainAscend` (`4326–4332`),
  `pivotForStepUp` (`4150`), `ascentRamSlide` slide-back semantics (`928–932`), pillar-recover actuator
  (`4249–4284`), `StairUpBreak` mining order + anti-suffocation ceiling dig, `fellOffPath`/`safetyRepath`
  backbone (`1042`,`1210`). DELETE (spec §5, all default-`false`): `walkerVerticalResync`,
  `walkerAscentRamJitterImmune`, `walkerStepUpCrestReach`, `walkerLevelRiserJump`, `walkerPadRamBreak`,
  `walkerStickyDig` residue — removed ONLY in Unit 5.
- **Do NOT delete any KEEP recovery on suspicion.** The §8.1 KEEP-redundancy open question
  (`ascentRamSlide`/`arcWedge`/`aboveNodeStall` overlap) is resolved by live A/B in Unit 6, BEFORE the
  final absorbed-logic list is committed — surface it as an explicit step, not a silent assumption.
- **Replay/arena is a regression guard, NOT the acceptance gate.** The gametest/replay path is
  client-only and a server FakePlayer is immune to some physics/damage (spec §6.3). Live replay A/B
  (`PathArchiveRecorder` → `mc.debug.replay`, OFF vs ON) is the flip gate. The flag flips to default-ON
  only on a clean live A/B (Unit 6).
- **task#83 is a hard blocker for Unit 5 and the Unit 6 flip.** `config/worlddriver/replays/baseline-flags.json`
  is stale (committed 2026-06-28, predates the 2026-07-04 flag-flip; still lists the DELETE flags). It
  must be re-baselined against current shipped config first, or the A/B has no valid regression floor.
- **New `@GameTest` folds into `AgentGameTestTerrain.java`** (house rule: no new gametest file). It is
  auto-discovered because `AgentGameTestTerrain` is a `@GameTestHolder`; guard every new test with
  `AgentGameTestSupport.gtOnlySkips("<name>")` so `AGENT_GT_ONLY` can target it (spec §6.2, §67 memory).
- **Touching shared logic → grep sibling verbs.** Any edit to a step-advance gate / recovery family
  must `grep` the sibling gates in `Walker.java` for the same pose before landing (memory §61/§62).
- **Build/test.** Full build (all 3 loaders): `./gradlew build`. Full gametest suite:
  `./gradlew :neoforge:runGameTestServer`. Single arena: `AGENT_GT_ONLY=<name> ./gradlew :neoforge:runGameTestServer`.
  Trust ONLY the `required tests passed` line (the `TOTAL` line masks required results, memory
  reference). Known-flaky lottery arenas may be ignored ONLY if they are the sole failures.
- **Commit after each Task** (`git commit`, do not push). If on the default branch, branch first.

---

### Task 1 (Unit 1): scaffolding + no-op wiring

Add the `Movement` interface, `MovementStatus` enum, `MovementContext`, an empty `AscendMovement`, the
`BotConfig.walkerAscendMovement=false` flag wired to `mc.bot.setting`, and the delegation branch that —
with the flag OFF — is provably skipped and allocates nothing.

**Files:**
- Create: `common/src/main/java/net/magicterra/worlddriver/bot/movement/Movement.java`
- Create: `common/src/main/java/net/magicterra/worlddriver/bot/movement/MovementStatus.java`
- Create: `common/src/main/java/net/magicterra/worlddriver/bot/movement/MovementContext.java`
- Create: `common/src/main/java/net/magicterra/worlddriver/bot/movement/AscendMovement.java`
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/BotConfig.java` (add flag near the walker-flag
  region, after `walkerChainMount` at line 1817)
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/SettingsSnapshot.java` (add `snap.put(...)` in
  the walker block near line 95)
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/SettingsCommand.java` (add the
  `instanceof Boolean` apply near line 353)
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/movement/Walker.java` (add `ascendMovement`
  field + `forceFellOffPath` field near 159–162; add `isMigratedAscent` static; insert delegation at
  4287; OR `forceFellOffPath` into `fellOffPath` at 1042)
- Test: `neoforge/src/main/java/net/magicterra/worlddriver/neoforge/AgentGameTestTerrain.java` (new
  `@GameTest ascendMovementNoopArena`)

**Interfaces:**
- Produces: `public interface Movement { MovementStatus updateState(MovementContext ctx); }`
- Produces: `public enum MovementStatus { PREP, RUNNING, SUCCESS, UNREACHABLE, FAILED }`
- Produces: `Walker.isMigratedAscent(String move)` → `static boolean` (matches `"stepUp"`,
  `"stairUpBreak"`, `"diagUp"`).
- Produces: `MovementContext` static allocation counter `MovementContext.ALLOC_COUNT` (test-only seam)
  proving the OFF branch never constructs one.
- Consumes (delegation): `edge` (`Move.Edge edge = edgeAt(step);` `Walker.java:2346`), `p`
  (`Player p = a.player();` `644`), `foot` (`717`), `path`/`step` (members), `Step.WALKING` enum (`43`).

**Steps:**

- [ ] **Step 1: write the failing no-op arena** — append to `AgentGameTestTerrain.java` (mirror the
  header + `gtOnlySkips` guard + save/restore pattern of `ascentSpeedArena:871`). It runs the existing
  cardinal staircase with `walkerAscendMovement` OFF and asserts the machine allocated nothing and the
  climb still tops out (byte-identical OFF).

```java
@GameTest(template = "empty", timeoutTicks = 100000)
public static void ascendMovementNoopArena(GameTestHelper helper) {
    if (AgentGameTestSupport.gtOnlySkips("ascendMovementNoopArena")) { helper.succeed(); return; } // gt-filter
    ServerLevel level = helper.getLevel();
    final int cx = 440, cz = 620, baseY = 210, stepCount = 6;   // disjoint region (away from ascentSpeedArena cz=440)
    for (int dx = -10; dx <= -1; dx++)
        for (int dz = -2; dz <= 2; dz++)
            level.setBlockAndUpdate(new BlockPos(cx + dx, baseY, cz + dz), Blocks.STONE.defaultBlockState());
    for (int i = 0; i < stepCount; i++) {
        int sy = baseY + 1 + i;
        for (int dx = 2 * i; dx <= 2 * i + 1; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int y = baseY; y <= sy; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
    }
    final int topSurf = baseY + stepCount;
    final int ascEndX = cx + 2 * stepCount - 1;
    for (int dx = 2 * stepCount; dx <= 2 * stepCount + 12; dx++)
        for (int dz = -2; dz <= 2; dz++)
            level.setBlockAndUpdate(new BlockPos(cx + dx, topSurf, cz + dz), Blocks.STONE.defaultBlockState());
    BlockPos goal = new BlockPos(cx + 2 * stepCount + 10, topSurf + 1, cz);

    boolean ob = BotConfig.allowBreak, op = BotConfig.allowPlace, oam = BotConfig.walkerAscendMovement;
    long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
    BotConfig.allowBreak = false; BotConfig.allowPlace = false;
    BotConfig.walkerAscendMovement = false;             // OFF leg → machine must be inert
    BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2; BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
    try {
        ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx - 9 + 0.5, baseY + 1, cz + 0.5);
        FakePlayer fp = av.fakePlayer();
        grantWaterEffects(fp);
        LevelWorldView w = new LevelWorldView(level, fp);
        Walker walker = new Walker();
        walker.setGoal(new Goal.Block(goal));
        long allocBefore = MovementContext.ALLOC_COUNT;
        Walker.Step s = Walker.Step.WALKING;
        for (int t = 0; t < 500 && s == Walker.Step.WALKING; t++) { s = walker.tick(av, w); av.step(); }
        boolean reachedTop = fp.getX() > ascEndX && fp.getY() >= topSurf + 1 - 0.4;
        long allocated = MovementContext.ALLOC_COUNT - allocBefore;
        WorldDriverCommon.LOG.info("[ascendMovementNoopArena] step={} pos=({},{},{}) reachedTop={} ctxAllocated={}",
                s, fp.getX(), fp.getY(), fp.getZ(), reachedTop, allocated);
        if (allocated != 0)
            throw new GameTestAssertException("ascendMovementNoopArena: flag OFF but MovementContext was constructed "
                    + allocated + " times — the OFF branch is not a zero-cost no-op (spec §8.3)");
        if (!reachedTop)
            throw new GameTestAssertException("ascendMovementNoopArena: did not reach the flat top: pos=("
                    + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") step=" + s);
    } finally {
        BotConfig.allowBreak = ob; BotConfig.allowPlace = op; BotConfig.walkerAscendMovement = oam;
        BotConfig.pathfinderSliceMs = osl; BotConfig.pathfinderMaxMs = omm;
    }
    helper.succeed();
}
```

- [ ] **Step 2: run RED** — `AGENT_GT_ONLY=ascendMovementNoopArena ./gradlew :neoforge:runGameTestServer`.
  Expected: **compile failure** — `cannot find symbol: variable MovementContext` /
  `variable walkerAscendMovement`. That is the RED.

- [ ] **Step 3: create the interface + enum**

`Movement.java`:
```java
package net.magicterra.worlddriver.bot.movement;

/** A single migrated move that advances itself one tick against {@link MovementContext},
 *  emitting inputs via the context and returning its own status. The Baritone MovementState
 *  form: the move OWNS its PREP→BREAK→ASCEND→CONFIRM sequence and its bounded timeout→cancel. */
public interface Movement {
    /** Advance this move one tick against ctx; emit inputs via ctx; return status. */
    MovementStatus updateState(MovementContext ctx);
}
```

`MovementStatus.java`:
```java
package net.magicterra.worlddriver.bot.movement;

/** Result of one {@link Movement#updateState} tick. SUCCESS is a clean pointer-advance; both
 *  failure codes fold into the existing fellOffPath re-route but carry different telemetry —
 *  UNREACHABLE = "could not close the gap within budget" (the task#82 case), FAILED = "edge is
 *  malformed / the world changed under us". */
public enum MovementStatus {
    PREP,        // still aligning/positioning; not yet actuating the core maneuver
    RUNNING,     // actuating (breaking/jumping/rising); hold the step pointer
    SUCCESS,     // arrived on the destination stand cell → caller does step++
    UNREACHABLE, // bounded timeout with no actuation progress → caller re-routes (blacklist node)
    FAILED       // hard error (edge/world inconsistency) → caller re-routes
}
```

- [ ] **Step 4: create `MovementContext`** — the data the machine owns, derived from exactly the
  executor state the ascent path reads today (spec §3.2). Keep the constructor cheap but real; expose
  the pose/edge/world/input handles the machine needs.

```java
package net.magicterra.worlddriver.bot.movement;

import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;

/** Per-delegated-tick bundle handed to {@link AscendMovement}. Constructed ONCE per delegated tick
 *  from live Walker state; the machine retains no reference across ticks except its own owned
 *  sub-state (which AscendMovement holds). See spec §3.2. */
public final class MovementContext {
    /** TEST SEAM (spec §8.3): total MovementContext instances ever constructed. The no-op arena
     *  asserts this does NOT move while walkerAscendMovement is OFF — proving the OFF branch never
     *  allocates a context. Volatile because gametest threads read it. */
    public static volatile long ALLOC_COUNT = 0;

    public final Player p;             // pose/velocity: getX/Y/Z, getDeltaMovement, onGround, horizontalCollision, isInWater
    public final WorldView world;      // isSolid/isPassable/isHazard for the BREAK phase (mirrors StairUpBreak.eval)
    public final Avatar avatar;        // selectTool/aimAtBlock/breakHold/placeOn/holdPillarBlock + commandForward/Jump/Sneak
    public final Move.Edge edge;       // current edge: to (stand cell), toBreak, toPlace, move name
    public final BlockPos foot;        // grounded foot cell this tick
    public final BlockPos node;        // path.get(step) — the stand-cell node (== edge.to for an ascent)
    public final int maxJumpUp;        // world.maxJumpUpBlocks()
    public final int maxStepUp;        // world.maxStepUpBlocks()
    public final BlockPos prevNode;    // path.get(step-1) or null — chainAscend peek
    public final BlockPos prevNode2;   // path.get(step-2) or null — chainAscend peek

    public MovementContext(Player p, WorldView world, Avatar avatar, Move.Edge edge,
                           BlockPos foot, BlockPos node, int maxJumpUp, int maxStepUp,
                           BlockPos prevNode, BlockPos prevNode2) {
        ALLOC_COUNT++;
        this.p = p; this.world = world; this.avatar = avatar; this.edge = edge;
        this.foot = foot; this.node = node; this.maxJumpUp = maxJumpUp; this.maxStepUp = maxStepUp;
        this.prevNode = prevNode; this.prevNode2 = prevNode2;
    }
}
```

- [ ] **Step 5: create the empty `AscendMovement` stub** — returns `PREP` (holds the pointer, never
  churns) until Unit 2 fills it in. Never reached with the flag OFF, so the no-op arena is unaffected.

```java
package net.magicterra.worlddriver.bot.movement;

/** Per-move state machine for the ascent/climb family (stepUp, stairUpBreak, diagUp). Owns its own
 *  PREP→BREAK→ASCEND→CONFIRM sequence, input emission, and bounded timeout→cancel. See spec §4.
 *  Unit 1: empty stub (flag stays OFF; never invoked). Filled in Units 2–5. */
public final class AscendMovement implements Movement {
    @Override public MovementStatus updateState(MovementContext ctx) {
        return MovementStatus.PREP;   // stub — real transitions land in Units 2–5
    }
}
```

- [ ] **Step 6: add the `BotConfig` flag** — insert after `walkerChainMount` (`BotConfig.java:1817`):

```java
/** task#82: route ascent/climb edges (stepUp/stairUpBreak/diagUp) through the per-move
 *  AscendMovement state machine (own PREP→BREAK→ASCEND→CONFIRM + bounded timeout→cancel).
 *  Default OFF = the tickInner delegation branch is skipped and legacy ascent handling runs
 *  byte-identically (spec §5). Flip ON only on a clean live A/B (Unit 6). Wired to mc.bot.setting. */
public static volatile boolean walkerAscendMovement = false;
```

  In `SettingsSnapshot.java` (walker block, near line 95) add:
  `snap.put("walkerAscendMovement", BotConfig.walkerAscendMovement);`

  In `SettingsCommand.java` (near line 353, alongside the other walker toggles) add:
```java
if (params.get("walkerAscendMovement") instanceof Boolean wam) {
    BotConfig.walkerAscendMovement = wam;
    applied.add("walkerAscendMovement");
}
```

- [ ] **Step 7: add `Walker` fields + `isMigratedAscent`** — near the ascent-state fields
  (`Walker.java:159–162`) add:
```java
private final AscendMovement ascendMovement = new AscendMovement();   // task#82 per-move machine (drives only when walkerAscendMovement is ON)
private boolean forceFellOffPath;   // task#82: AscendMovement returned UNREACHABLE/FAILED last delegated tick → OR into fellOffPath (line 1042) so the proven re-route fires
```
  Add the static matcher (place near `edgeAt`, `Walker.java:5713`):
```java
/** task#82 migrated ascent move-names (spec §3.3): exactly the three the recovery gates already
 *  test — a plain stepUp, its break-carrying sibling stairUpBreak, and the diagonal diagUp.
 *  stepUp2 (+2, horse) and parkourAscend* are excluded (spec §10). Cheap string check so the OFF
 *  delegation short-circuit costs nothing. */
static boolean isMigratedAscent(String move) {
    return "stepUp".equals(move) || "stairUpBreak".equals(move) || "diagUp".equals(move);
}
```

- [ ] **Step 8: OR `forceFellOffPath` into the recovery spine** — at `Walker.java:1042`, prepend the
  field and consume it (it was set on the PREVIOUS delegated tick at 4287; the 1-tick latency is
  benign — this reuses the same spine `UNREACHABLE` maps to):
```java
boolean fellOffPath = forceFellOffPath || arcWedge || arcProgWedge || ascentRamSlide
        || ascentRamSlideJitterImmune || descentRamStuck || verticalResync || aboveNodeStall
        || (path != null && step < path.size()
            && Math.abs(path.get(step).getY() - foot.getY()) > world.maxJumpUpBlocks() + 2);
forceFellOffPath = false;   // consume: one fold per UNREACHABLE/FAILED
```
  The existing fellOffPath foot-search already blacklists `path.get(step)` (== `edge.to`), so no extra
  blacklist wiring is needed (spec §3.3).

- [ ] **Step 9: insert the delegation branch** — at `Walker.java:4287`, immediately BEFORE the
  `// Baritone MovementAscend jump-timing applies whenever we actually JUMP a cardinal step` comment
  (after the legacy pillar-recover block that ends at 4286). `edge`, `p`, `foot`, `path`, `step` are all
  in scope here.
```java
// task#82 per-move ASCENT machine (spec §3.3). Flag FIRST so OFF short-circuits with no allocation.
// isMigratedAscent is a cheap string check; !isInWater keeps water ascents on legacy dig-recovery.
if (BotConfig.walkerAscendMovement && edge != null && isMigratedAscent(edge.move) && !p.isInWater()) {
    MovementContext ctx = new MovementContext(
            p, world, a, edge, foot, path.get(step),
            world.maxJumpUpBlocks(), world.maxStepUpBlocks(),
            step >= 1 ? path.get(step - 1) : null,
            step >= 2 ? path.get(step - 2) : null);
    switch (ascendMovement.updateState(ctx)) {
        case SUCCESS -> { step++; noProgressStep = -1; noStepProgressTicks = 0; }   // machine ratified arrival → advance (mirrors the within-gate advance at line 2284)
        case PREP, RUNNING -> { return Step.WALKING; }                              // machine drove inputs this tick → hold the pointer
        case UNREACHABLE, FAILED -> { forceFellOffPath = true; }                    // fold into the existing re-route (consumed next tick at line 1042)
    }
}
```
  **Implementation notes (code vs spec §3.3):**
  1. The natural within/passed advance at `Walker.java:2284` runs EARLIER in the tick than this branch.
     In the task#82 dead-zone pose `within` cannot fire (needs `cur2<0.45`), so for the migrated pose it
     is a no-op and the machine is the sole advance authority. If a clean climb tops out such that
     `within` fires at 2284 AND the machine's CONFIRM would also SUCCESS at 4287, guard against a
     double-advance: in Unit 2, gate the 2284 advance with `!(BotConfig.walkerAscendMovement && edge != null
     && isMigratedAscent(edge.move) && !p.isInWater())` so only ONE authority advances a migrated edge.
     This guard is active ONLY when the flag is ON, preserving byte-identical OFF.
  2. The Unit 1 stub returns `PREP` (hold) — never reached with the flag OFF, so this branch stays
     inert for the whole unit; the no-op arena runs entirely on legacy.

- [ ] **Step 10: run GREEN** — the no-op arena plus every ascent regression guard must stay green with
  the flag at its OFF default:
  `AGENT_GT_ONLY=ascendMovementNoopArena,ascentSpeedArena,diagonalAscentSpeedArena,selfShaftDigUpArena,stepUpCrestOrbitArena ./gradlew :neoforge:runGameTestServer`
  Expected: `required tests passed` — `ctxAllocated=0` logged; all five green.

- [ ] **Step 11: build all loaders + commit** — `./gradlew build` (common+neoforge+fabric compile).
  Then `git commit -m "task#82 Unit1: Movement scaffolding + default-OFF delegation no-op"`.

---

### Task 2 (Unit 2): PREP/ALIGN + `stepUp` delegation

Implement PREP (absorb `pivotForStepUp` alignment + the jump-timing square-up gate) and ASCEND for a
plain `stepUp` (empty `toBreak`), then prove the machine's ON path reproduces legacy jump-timing.

**Files:**
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/movement/AscendMovement.java`
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/movement/Walker.java` (add the double-advance
  guard from Task 1 note 1, at line 2284's advance)
- Test: `neoforge/.../AgentGameTestTerrain.java` (convert `ascentSpeedArena:871` into a two-leg A/B)

**Interfaces:**
- Consumes: `MovementContext.{p, avatar, edge, foot, node, maxJumpUp, prevNode, prevNode2}`.
- Produces: `AscendMovement` now drives PREP (align/pivot + square-up) and ASCEND (jump-timing) for
  `edge.move.equals("stepUp")`, returning `PREP`/`RUNNING`/`SUCCESS`. It holds per-episode state:
  `private int episodeTicks; private int actuationTicks;` (reset when `node` changes).

**Steps:**

- [ ] **Step 1: make `ascentSpeedArena` a two-leg A/B** (RED) — wrap the existing body in
  `for (int leg = 0; leg < 2; leg++)` toggling `BotConfig.walkerAscendMovement = (leg == 1)`, and assert
  BOTH legs `reachedTop` and `ascBps >= 1.5`. Save/restore the flag in the existing `finally`. The ON
  leg (leg 1) is the new assertion. Concretely, add to the existing `try` header:
```java
boolean oam = BotConfig.walkerAscendMovement;
```
  wrap the sampling loop + assertions in the `for (leg...)`, set `BotConfig.walkerAscendMovement = (leg == 1);`
  at the top of the loop, log `leg` in the existing `[ascentSpeedArena]` line, and restore
  `BotConfig.walkerAscendMovement = oam;` in `finally`. With the Unit 1 stub returning `PREP`, leg 1 HOLDS
  the pointer forever → the bot never climbs → `reachedTop=false`. That is the RED.

- [ ] **Step 2: run RED** — `AGENT_GT_ONLY=ascentSpeedArena ./gradlew :neoforge:runGameTestServer`.
  Expected FAIL: `ascentSpeedArena: did not reach the flat top ... step=WALKING` on leg 1 (the stub
  never actuates).

- [ ] **Step 3: implement PREP + ASCEND for `stepUp`** in `AscendMovement.updateState`. Port the jump-
  timing block VERBATIM (`Walker.java:4291–4337`): compute `xA/zA/flatDist/sideDist/lateralMotion` off
  `ctx.p`/`ctx.node`, the `chainAscend` loosen from `ctx.prevNode`/`prevNode2` (`4326–4332`, gated on
  `BotConfig.walkerChainMount`), the `aligned` gate (`4333–4335`), `sprintAscend`, and
  `ascendJumpReady`. Drive inputs through `ctx.avatar` (`commandForward`, `commandJump`, `commandSneak`)
  and aim via the same pivot logic `pivotForStepUp` uses (`Walker.java:4150`, `STEPUP_AIM_TOLERANCE_DEG=40`).

```java
public final class AscendMovement implements Movement {
    private BlockPos episodeNode;   // resets per-episode state when the target node changes
    private int episodeTicks;
    private int actuationTicks;     // ticks we actually pressed jump / made vertical progress

    @Override public MovementStatus updateState(MovementContext ctx) {
        if (!ctx.node.equals(episodeNode)) { episodeNode = ctx.node; episodeTicks = 0; actuationTicks = 0; }
        episodeTicks++;

        // CONFIRM first: foot on the stand cell (real `within`) → SUCCESS.
        double dx = (ctx.node.getX() + 0.5) - ctx.p.getX();
        double dz = (ctx.node.getZ() + 0.5) - ctx.p.getZ();
        double cur2 = dx * dx + dz * dz;
        double dyNode = ctx.node.getY() - ctx.p.getY();
        if (cur2 < WalkerConstants.REACH_DIST_SQ && Math.abs(dyNode) < 0.5) return MovementStatus.SUCCESS;

        // A stepUp/diagUp has an empty toBreak → straight to ASCEND (BREAK is Unit 3).
        // PREP/ASCEND: the ported jump-timing block. cardinalUp = a +1 cardinal step.
        boolean diagUp = "diagUp".equals(ctx.edge.move);
        int upDy = ctx.node.getY() - ctx.foot.getY();
        boolean cardinalUp = !diagUp && upDy >= 1
                && (ctx.node.getX() == ctx.foot.getX() || ctx.node.getZ() == ctx.foot.getZ());
        // ... (verbatim port of Walker.java:4295-4337: flatDist/sideDist/lateralMotion, chainAscend,
        //      aligned, sprintAscend, ascendJumpReady). Drive: ctx.avatar.commandForward(1f);
        //      ctx.avatar.setSprinting(sprintAscend via Player); ctx.avatar.commandJump(ascendJumpReady);
        //      pivot in place (cut forward + jump) while stepHeadingErr > STEPUP_AIM_TOLERANCE_DEG.
        if (/* actuated a jump this tick */ true) actuationTicks++;
        // Timeout → UNREACHABLE lands in Unit 4; Unit 2 returns RUNNING/PREP only.
        return /* aligned && jumping */ MovementStatus.RUNNING; // else PREP while squaring up
    }
}
```
  Keep `WalkerConstants` package-private access (same package). Import `net.magicterra.worlddriver.bot.BotConfig`,
  `net.minecraft.core.BlockPos`.

- [ ] **Step 4: add the double-advance guard** (Task 1 note 1) — at the within/passed advance
  `Walker.java:2284` (`step++;`), gate it so a migrated ascent edge advances via the machine only:
```java
if (within || passed) {
    Move.Edge se = edgeAt(step);
    boolean machineOwns = BotConfig.walkerAscendMovement && se != null
            && isMigratedAscent(se.move) && !p.isInWater();
    if (!machineOwns) { step++; /* ...existing post-advance bookkeeping... */ }
}
```
  (Adapt to the exact existing `within`/`passed` control flow around 2280–2290; grep the sibling advances
  `crossedWalkNode`/`stepUpCrestReach` at 2191/2195 to confirm none double-advance a migrated edge.)

- [ ] **Step 5: run GREEN** — `AGENT_GT_ONLY=ascentSpeedArena ./gradlew :neoforge:runGameTestServer`.
  Expected: `required tests passed` — BOTH legs `reachedTop=true`, `ascBps >= 1.5`; the ON leg's b/s
  within noise of the OFF leg (faithful jump-timing).

- [ ] **Step 6: regression sweep + build + commit** —
  `AGENT_GT_ONLY=ascendMovementNoopArena,ascentSpeedArena,selfShaftDigUpArena,stepUpCrestOrbitArena ./gradlew :neoforge:runGameTestServer`,
  then `./gradlew build`, then
  `git commit -m "task#82 Unit2: PREP/ALIGN + stepUp ASCEND behind walkerAscendMovement"`.

---

### Task 3 (Unit 3): BREAK phase for `stairUpBreak` (and `diagUp`)

Implement BREAK (mine `srcUp2`/`to`/`head` in `StairUpBreak.eval` order + the anti-suffocation ceiling
dig), and add `diagUp` centring so the diagonal families run on the machine.

**Files:**
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/movement/AscendMovement.java`
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/movement/Walker.java` (gate the legacy
  pillar-recover arming so the machine is sole driver when ON — see note 1)
- Test: `neoforge/.../AgentGameTestTerrain.java` (`selfShaftDigUpArena:797` +
  `diagonalAscentSpeedArena:967` two-leg A/B, mirroring Task 2 Step 1)

**Interfaces:**
- Consumes: `MovementContext.{world, avatar, edge.toBreak, foot, node}`.
- Produces: `AscendMovement` now handles a non-empty `edge.toBreak` (`stairUpBreak`) — a BREAK sub-state
  returning `RUNNING` while a cell is still solid, `UNREACHABLE` on an unbreakable hazard/fluid or a
  blown per-riser dig budget, transitioning to ASCEND once all planned cells read clear. `diagUp`
  (empty `toBreak`) centres on the diagonal (`Walker.java:4429`) and runs ASCEND.

**Steps:**

- [ ] **Step 1: two-leg A/B for the break/diagonal arenas** (RED) — apply the same
  `for (leg 0..1)` + `walkerAscendMovement=(leg==1)` + save/restore wrap from Task 2 Step 1 to BOTH
  `selfShaftDigUpArena` (assert both legs `worstBackslide <= maxDryFall+1` AND reach `targetY`) and
  `diagonalAscentSpeedArena` (assert both legs `reachedTop` AND `ascBps >= 2.5`). With BREAK/diagUp not
  yet implemented, the ON leg either holds the pointer (stub) or fails to carve the shaft. That is RED.

- [ ] **Step 2: run RED** —
  `AGENT_GT_ONLY=selfShaftDigUpArena,diagonalAscentSpeedArena ./gradlew :neoforge:runGameTestServer`.
  Expected FAIL: `selfShaftDigUpArena: dig-up did not reach the level` / `diagonalAscentSpeedArena: did
  not top out` on leg 1.

- [ ] **Step 3: implement BREAK** in `AscendMovement`. When `!ctx.edge.toBreak.isEmpty()`, before
  ASCEND, mine the planned cells in the `StairUpBreak.eval` order — `srcUp2` (`from.offset(0,2,0)`),
  destination foot `to`, destination head `head` (`moves/StairUpBreak.java:65–88`). For each still-solid
  cell drive `ctx.avatar.selectTool(cell); ctx.avatar.aimAtBlock(cell); ctx.avatar.breakHold(true);`
  and `return RUNNING`. Additionally clear the anti-suffocation ceiling first — the head cell
  `ctx.foot.offset(0,2,0)` — on the global `BotConfig.allowBreak` switch, EXEMPT from per-goto
  `forbidDig` (suffocation is death), mirroring the pillar-recover actuator (`Walker.java:4261–4271`).
  Return `UNREACHABLE` if a target cell is an unbreakable hazard/fluid (`!world.isPassable(cell) ||
  world.isHazard(cell)` on a non-solid, mirroring `StairUpBreak.eval` returning `null`) or the per-riser
  dig budget blows (use `WalkerConstants.WATER_CLIMB_DIG_COMMIT_CAP=1000` as the dry analogue reference,
  spec §4.2). Transition to ASCEND once all planned cells read clear (`!world.isSolid(cell)`).

```java
// BREAK (only for a non-empty toBreak, i.e. stairUpBreak):
if (!ctx.edge.toBreak.isEmpty()) {
    BlockPos ceiling = ctx.foot.offset(0, 2, 0);          // anti-suffocation: clear the head cell FIRST
    if (BotConfig.allowBreak && ctx.world.isSolid(ceiling)) {
        ctx.avatar.commandJump(false); ctx.avatar.commandForward(0f);
        ctx.avatar.selectTool(ceiling); ctx.avatar.aimAtBlock(ceiling); ctx.avatar.breakHold(true);
        return MovementStatus.RUNNING;
    }
    for (BlockPos cell : ctx.edge.toBreak) {              // srcUp2, to, head — StairUpBreak order
        if (ctx.world.isSolid(cell)) {
            if (++breakTicks > WalkerConstants.WATER_CLIMB_DIG_COMMIT_CAP) return MovementStatus.UNREACHABLE;
            ctx.avatar.selectTool(cell); ctx.avatar.aimAtBlock(cell); ctx.avatar.breakHold(true);
            return MovementStatus.RUNNING;
        }
        if (ctx.world.isHazard(cell) || !ctx.world.isPassable(cell)) return MovementStatus.UNREACHABLE;
    }
    ctx.avatar.breakHold(false); breakTicks = 0;          // all cells clear → fall into ASCEND
}
```
  Add `private int breakTicks;` reset per-episode alongside `episodeTicks`. **ColumnRadius /
  LeashHardRadius** (spec §4.2) are enforced at PLAN time by the constraints package
  (`bot/pathfinder/constraints/`), so BREAK re-checks only the dynamic anti-suffocation ceiling cell —
  it mines exactly `edge.toBreak` (already constraint-validated) plus that one ceiling cell.

- [ ] **Step 4: `diagUp` centring** — in ASCEND, when `"diagUp".equals(ctx.edge.move)`, centre on the
  diagonal toward the node (`latX = (node.x+0.5)-p.x; latZ = (node.z+0.5)-p.z;`, `Walker.java:4429`) and
  drive forward+jump like a stepUp WITHOUT forcing sprint (the diagonal corner-ram A/B-disproof,
  `Walker.java:4365–4372`). No new sprint composition.

- [ ] **Step 5: gate the legacy pillar-recover arming when the machine drives** (note 1) — so the
  machine is the sole driver for a migrated ascent when the flag is ON. At `Walker.java:4245` (the
  `overJump || slowDiagUpPillar` arm) and `1083` (`fellBelowRoute` arm), add
  `&& !(BotConfig.walkerAscendMovement && edge != null && isMigratedAscent(edge.move) && !p.isInWater())`.
  **Implementation note (code vs spec §3.2):** the spec says the pillar-recover sub-state is "moved off
  Walker's field list", but the KEEP set keeps the legacy pillar-recover actuator resident for the OFF
  path, so it CANNOT be moved without breaking byte-identical OFF. Instead: the legacy
  `pillarRecover*` fields (`Walker.java:159–162`) stay for OFF; `AscendMovement` holds its OWN parallel
  pillar-recover sub-state (`private int pillarLatch, pillarPeakY, pillarStallTicks; private BlockPos
  pillarCell;`) used only while it drives (flag ON). ASCEND absorbs the actuator by re-implementing
  `Walker.java:4249–4284` against `ctx` (spec §4.3).

- [ ] **Step 6: run GREEN** —
  `AGENT_GT_ONLY=selfShaftDigUpArena,diagonalAscentSpeedArena ./gradlew :neoforge:runGameTestServer`.
  Expected: `required tests passed` — both arenas green on BOTH legs.

- [ ] **Step 7: full ascent-suite sweep + build + commit** —
  `AGENT_GT_ONLY=ascendMovementNoopArena,ascentSpeedArena,diagonalAscentSpeedArena,selfShaftDigUpArena,stepUpCrestOrbitArena ./gradlew :neoforge:runGameTestServer`,
  then `./gradlew build`, then
  `git commit -m "task#82 Unit3: BREAK phase (stairUpBreak) + diagUp on AscendMovement"`.

---

### Task 4 (Unit 4): timeout → `UNREACHABLE` + task#82 RED→GREEN arena

Add the PREP bounded-timeout that returns `UNREACHABLE` for the dead-zone signature, and author the
task#82 arena proving the OFF leg wedges forever while the ON leg re-routes with zero churn. **This is
the unit that closes task#82.**

**Files:**
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/movement/AscendMovement.java`
- Test: `neoforge/.../AgentGameTestTerrain.java` (new `@GameTest ascendDeadZoneArena`, mirroring
  `stepUpCrestOrbitArena:499–622`; new `@GameTest ascendRerouteAdoptArena` using `adoptForTest`)

**Interfaces:**
- Produces: PREP returns `MovementStatus.UNREACHABLE` when `episodeTicks` exceeds a bounded budget
  (`STEPUP_FREEZE_TICKS=24` scaled to the ~1.2 s a healthy stepUp closes in — use a concrete
  `PREP_TIMEOUT_TICKS = STEPUP_FREEZE_TICKS * 2 = 48`) AND no actuation progress occurred
  (`actuationTicks == 0`, the dead-zone: `cur2 ∈ (0.45, 4.0)`, no `hCol`). The delegation's
  `UNREACHABLE|FAILED → forceFellOffPath` (Task 1 Step 9) folds it into the re-route.

**Steps:**

- [ ] **Step 1: write `ascendDeadZoneArena`** (RED) — mirror `stepUpCrestOrbitArena:499–622` exactly:
  disjoint absolute region; a `stairUpBreak`/`stepUp` tail whose stand cell is `+1` above the foot with
  a SOLID riser; hold the bot at the task#82 pose (`cur2 ≈ 1.48` in `(0.45, 4.0)`, `|dyNode|≈1`, no
  `hCol`) by `setPos`/`setDeltaMovement(0,0,0)` before AND after each tick; adopt a scripted plan via
  `walker.beginReplay(w, plan, planEdges, goal, node)` (replayMode DISABLES A* so the OFF leg is a TRUE
  permanent stall). Two legs: leg 0 = `walkerAscendMovement` OFF must WEDGE (`advanced[0]` false); leg 1
  = ON must re-route (assert the pointer is NOT pinned on the crest node for the whole window — i.e. the
  UNREACHABLE fold fires and `forceFellOffPath`/blacklist engages within the budget).

```java
@GameTest(template = "empty", timeoutTicks = 100000)
public static void ascendDeadZoneArena(GameTestHelper helper) {
    if (AgentGameTestSupport.gtOnlySkips("ascendDeadZoneArena")) { helper.succeed(); return; } // gt-filter
    ServerLevel level = helper.getLevel();
    final int cx = 420, cz = 700, baseY = 200;                 // disjoint region (crest arena is cz=560)
    final int footY = baseY + 1;
    for (int dx = -6; dx <= 10; dx++)
        for (int dz = -3; dz <= 3; dz++)
            for (int y = baseY - 2; y <= footY + 6; y++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
    for (int dx = -4; dx <= 2; dx++)
        level.setBlockAndUpdate(new BlockPos(cx + dx, baseY, cz), Blocks.STONE.defaultBlockState());   // floor
    // SOLID +1 riser at the stand cell (a stairUpBreak with a real toBreak); the foot approaches from +X.
    BlockPos foot   = new BlockPos(cx + 1, footY, cz);
    BlockPos riser  = new BlockPos(cx,     footY, cz);          // the +1 riser cell the edge must break (to)
    BlockPos node   = new BlockPos(cx,     footY + 1, cz);      // stand cell, +1 above the foot
    BlockPos cont   = new BlockPos(cx - 2, footY + 1, cz);
    BlockPos goalN  = new BlockPos(cx - 4, footY + 1, cz);
    level.setBlockAndUpdate(riser, Blocks.STONE.defaultBlockState());       // solid riser → stays solid (no dig gate arms)
    level.setBlockAndUpdate(new BlockPos(cx, footY - 1, cz), Blocks.STONE.defaultBlockState()); // floor under the riser
    Goal goal = new Goal.Block(goalN);
    // Held task#82 pose: foot at footY (|dyNode|≈1 to the node at footY+1), ~1.22 b east of the riser
    // centre → cur2 ≈ 1.49 (in the (0.45, 4.0) dead-zone), no horizontalCollision.
    final double poseX = riser.getX() + 0.5 + 1.22;
    final double poseY = footY;
    final double poseZ = riser.getZ() + 0.5;

    boolean ob = BotConfig.allowBreak, op = BotConfig.allowPlace, odbg = BotConfig.walkerDebug, oam = BotConfig.walkerAscendMovement;
    long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
    BotConfig.allowBreak = false; BotConfig.allowPlace = false;   // no carving: the stall must be the dead-zone, not a dig
    BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2; BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
    try {
        boolean[] rerouted = new boolean[2];
        int[] pinnedTicks = new int[2];
        double[] obsCur2 = { Double.NaN, Double.NaN };
        for (int leg = 0; leg < 2; leg++) {
            BotConfig.walkerAscendMovement = (leg == 1);
            BotConfig.walkerDebug = true;
            ServerPlayerAvatar av = ServerPlayerAvatar.create(level, poseX, poseY, poseZ);
            FakePlayer fp = av.fakePlayer();
            grantWaterEffects(fp);
            LevelWorldView w = new LevelWorldView(level, fp);
            Walker walker = new Walker();
            List<BlockPos> plan = List.of(foot, node, cont, goalN);
            List<Move.Edge> planEdges = List.of(
                    new Move.Edge(foot, 10, List.of(), List.of(), "walk"),
                    new Move.Edge(node, 25, List.of(riser), List.of(), "stairUpBreak"),   // +1 stand cell, riser to break
                    new Move.Edge(cont, 10, List.of(), List.of(), "walk"),
                    new Move.Edge(goalN, 10, List.of(), List.of(), "walk"));
            walker.beginReplay(w, plan, planEdges, goal, node);   // step points at the stairUpBreak node
            Walker.Step s = Walker.Step.WALKING;
            for (int t = 0; t < 200 && s == Walker.Step.WALKING; t++) {
                fp.setPos(poseX, poseY, poseZ); fp.setDeltaMovement(0, 0, 0);
                s = walker.tick(av, w); av.step();
                fp.setPos(poseX, poseY, poseZ); fp.setDeltaMovement(0, 0, 0);
                BlockPos pn = walker.pathNode();
                if (pn != null && pn.equals(node)) {
                    double dx = (node.getX() + 0.5) - poseX, dz = (node.getZ() + 0.5) - poseZ;
                    obsCur2[leg] = dx * dx + dz * dz; pinnedTicks[leg]++;
                } else { rerouted[leg] = true; }   // pointer left the stairUpBreak node → the re-route fired
            }
            WorldDriverCommon.LOG.info("[ascendDeadZoneArena] leg={} flagOn={} rerouted={} pinnedTicks={} heldCur2={} step={}",
                    leg, leg == 1, rerouted[leg], pinnedTicks[leg], String.format(Locale.ROOT, "%.3f", obsCur2[leg]), s);
        }
        if (!(obsCur2[0] > 0.45 && obsCur2[0] < 4.0))
            throw new GameTestAssertException("ascendDeadZoneArena: held pose cur2=" + obsCur2[0] + " not in the dead-zone (0.45, 4.0) — re-tune poseX");
        if (rerouted[0])
            throw new GameTestAssertException("ascendDeadZoneArena: flag OFF but the pointer left the node — the dead-zone wedge did not reproduce (OFF must pin the whole window; beginReplay kills repath)");
        if (!rerouted[1])
            throw new GameTestAssertException("ascendDeadZoneArena: flag ON but the pointer stayed PINNED on the stairUpBreak node " + pinnedTicks[1] + " ticks — AscendMovement never returned UNREACHABLE → no fellOffPath fold (task#82 NOT closed)");
    } finally {
        BotConfig.allowBreak = ob; BotConfig.allowPlace = op; BotConfig.walkerDebug = odbg;
        BotConfig.walkerAscendMovement = oam; BotConfig.pathfinderSliceMs = osl; BotConfig.pathfinderMaxMs = omm;
    }
    helper.succeed();
}
```

  **Implementation note:** under `beginReplay` A* is disabled, so the ON leg's re-route cannot climb;
  the GREEN assertion checks the `UNREACHABLE`→`fellOffPath` TRIGGER directly (pointer un-pinned within
  the window / `forceFellOffPath` fires), NOT a successful climb (spec §6.1). The delegation's
  `forceFellOffPath` is consumed at line 1042 on the next tick; with replayMode the fellOffPath spine
  still cancels the continuation and clears the step, so `pathNode()` leaves the node.

- [ ] **Step 2: run RED** — `AGENT_GT_ONLY=ascendDeadZoneArena ./gradlew :neoforge:runGameTestServer`.
  Expected FAIL on leg 1: `flag ON but the pointer stayed PINNED ... 200 ticks` (Unit 3's `AscendMovement`
  returns `RUNNING`/`PREP` forever — no timeout yet).

- [ ] **Step 3: implement the PREP bounded-timeout** in `AscendMovement` — at the top of PREP/ASCEND,
  after `episodeTicks++`, if no actuation progress within budget, return `UNREACHABLE`:
```java
private static final int PREP_TIMEOUT_TICKS = WalkerConstants.STEPUP_FREEZE_TICKS * 2;   // ~2.4 s: a healthy stepUp closes in <12 ticks
// ... after CONFIRM check, before driving inputs:
boolean noProgress = actuationTicks == 0 && !ctx.p.horizontalCollision;   // dead-zone signature: stuck cur2, no ram
if (episodeTicks > PREP_TIMEOUT_TICKS && noProgress) return MovementStatus.UNREACHABLE;
```
  `actuationTicks` increments whenever a jump is pressed or the foot Y rises (real vertical progress),
  so a genuinely-climbing episode never trips the budget; only the dead-zone pin (jump gated OFF by the
  `aligned`/`flatDist` gate because the foot never closes) accrues `episodeTicks` with `actuationTicks==0`.
  Grep the sibling ascent gates (`ascentRamSlide:928`, `stepUpFreeze:4143`, `arcWedge:1019`) to confirm
  none of them would ALSO fire in this pose with the flag ON — they don't (no `hCol`, `+1` not `≥2`).

- [ ] **Step 4: write `ascendRerouteAdoptArena`** — the companion test using the recovery-ON seam
  `adoptForTest` (`Walker.java:504`), which keeps the full machinery ON (replayMode false). Build a real
  climbable staircase, `adoptForTest` a plan with the `stairUpBreak` tail, place the bot at the dead-zone
  pose ONCE (no per-tick re-pin), flip `walkerAscendMovement` ON + `allowBreak`/`allowPlace` ON, run to
  arrival, and assert the bot ends at/above the goal Y — exercising the full
  `UNREACHABLE → fellOffPath → fresh foot-search → climb` path. (Mirror `selfShaftDigUpArena`'s setup +
  `adoptForTest` call.) Assert `fp.getY() >= goalN.getY() - 1.0`.

- [ ] **Step 5: run GREEN** —
  `AGENT_GT_ONLY=ascendDeadZoneArena,ascendRerouteAdoptArena ./gradlew :neoforge:runGameTestServer`.
  Expected: `required tests passed` — OFF leg pins the whole window; ON leg re-routes with zero churn;
  the adopt arena climbs to the goal.

- [ ] **Step 6: full ascent-suite sweep + build + commit** —
  `AGENT_GT_ONLY=ascendMovementNoopArena,ascentSpeedArena,diagonalAscentSpeedArena,selfShaftDigUpArena,stepUpCrestOrbitArena,ascendDeadZoneArena,ascendRerouteAdoptArena ./gradlew :neoforge:runGameTestServer`,
  then `./gradlew build`, then
  `git commit -m "task#82 Unit4: PREP timeout→UNREACHABLE + dead-zone arena (closes task#82)"`.

---

### Task 5 (Unit 5): CONFIRM crest coverage + delete the unvalidated flags

> **BLOCKED on task#83** (refresh `config/worlddriver/replays/baseline-flags.json`). The stale baseline
> still lists the DELETE flags; task#83's refresh must drop them FIRST (spec §7). Do NOT start deletion
> until the refreshed baseline has landed. The refresh + this deletion are coupled: refresh baseline →
> then delete flags.

Finalize CONFIRM's arrival tolerance so it structurally subsumes the crest orbit, then remove the
never-validated default-OFF DELETE set and re-point/retire `stepUpCrestOrbitArena`.

**Files:**
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/movement/AscendMovement.java` (CONFIRM
  tolerance)
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/BotConfig.java` (delete 6 flag declarations:
  `walkerVerticalResync:1326`, `walkerLevelRiserJump:1350`, `walkerPadRamBreak:1374`,
  `walkerStepUpCrestReach:1562`, `walkerAscentRamJitterImmune:1596`, `walkerStickyDig:1892`)
- Modify: `common/.../bot/SettingsSnapshot.java` + `SettingsCommand.java` (drop the 6 `snap.put` +
  `instanceof Boolean` blocks — lines 83/84/85/93/95 in Snapshot; 303/307/311/343/351 in Command)
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/movement/Walker.java` (remove the gates that
  read them + now-dead fields — see Step 3)
- Modify: `common/.../bot/movement/WalkerConstants.java` (remove now-unused constants:
  `STEPUP_CREST_STALL_TICKS:337`, `STEPUP_CREST_REACH_SQ:347`, `RAM_JITTER_RECOVER_TICKS:269`,
  `RAM_RECOVER_DEBOUNCE:275`, `PAD_RAM_STALL_TICKS:380` — verify each has no other referent first)
- Modify: `neoforge/.../AgentGameTestTerrain.java` (re-point or retire `stepUpCrestOrbitArena:499`)

**Interfaces:**
- Produces: `AscendMovement` CONFIRM subsumes the crest orbit — its `cur2 < REACH_DIST_SQ && |dyNode| <
  0.5` arrival (Task 2 Step 3), with a short CONFIRM budget returning `UNREACHABLE` if arrival can't be
  ratified, replaces the `walkerStepUpCrestReach` relaxed-reach patch (spec §4.4).

**Steps:**

- [ ] **Step 0 (blocker check):** confirm task#83 landed — `git log --oneline -- config/worlddriver/replays/baseline-flags.json`
  shows a commit dated on/after 2026-07-15 that drops the DELETE flags. If not, STOP — do not proceed.

- [ ] **Step 1: re-point `stepUpCrestOrbitArena`** (RED) — it currently A/Bs `walkerStepUpCrestReach`
  (deleted this unit). Re-author it to A/B `walkerAscendMovement` instead: leg 0 OFF wedges at the crest
  orbit (as today), leg 1 ON advances because CONFIRM's arrival tolerance covers the crest pose. Replace
  `BotConfig.walkerStepUpCrestReach = (leg == 1);` (line 544) with `BotConfig.walkerAscendMovement =
  (leg == 1);`, the save/restore `ocr` with `oam`, and update the assertion messages. Because the flag
  it references (`walkerStepUpCrestReach`) is about to be deleted, this arena will not COMPILE until the
  re-point is done — do the re-point in this step. Run it FIRST (before deletion) to confirm the ON leg
  passes via CONFIRM:
  `AGENT_GT_ONLY=stepUpCrestOrbitArena ./gradlew :neoforge:runGameTestServer`. If leg 1 fails, tighten
  CONFIRM (add the short CONFIRM budget) until it advances — that is the RED→GREEN for CONFIRM crest
  coverage.

- [ ] **Step 2: delete the DELETE-set flag declarations + wiring** — remove the 6 declarations in
  `BotConfig.java` and their `SettingsSnapshot`/`SettingsCommand` entries (paths/lines above).

- [ ] **Step 3: remove the Walker gates that read them** (grep-verified sites from this tree):
  - `walkerVerticalResync`: the `verticalResync` block `Walker.java:1005–1011` and its `|| verticalResync`
    in the `fellOffPath` OR at 1042.
  - `walkerAscentRamJitterImmune`: the `ascentRamSlideJitterImmune` block `949–962`, its `||` in
    fellOffPath at 1042, and the now-dead fields `rawStepDwellTicks:188` / `ramRecoverLastFireDwell:189`
    (and their resets — grep `rawStepDwellTicks` / `ramRecoverLastFireDwell` and remove all).
  - `walkerStepUpCrestReach`: the `stepUpCrestReach` gate `2194–2208`, its consumer at the step-advance,
    and the `crestOrbitTicks:186` / `crestOrbitStep:187` fields + the `2186–2193` counter block.
  - `walkerLevelRiserJump`: the `levelRiserJump` gate `4958–4960` (+ any `levelRiserRam` scaffolding
    that becomes dead — grep `levelRiser`).
  - `walkerPadRamBreak`: the pad-break gate `5144`+ (grep `walkerPadRamBreak` / `PAD_RAM`).
  - `walkerStickyDig` residue (already killed §66): `stickyDigPos:123`, the block `804`, the two
    `stickyDigTicks` arms `3033`/`3227` — remove the `walkerStickyDig ||` disjuncts (keep the
    `walkerDigAimPriority` path, which is the live one).
  **Grep-sibling rule:** for each removed gate, grep its pose in `Walker.java` to confirm no live gate
  depended on its side effects.

- [ ] **Step 4: remove now-unused `WalkerConstants`** (only after Step 3, verify zero referents):
  `STEPUP_CREST_STALL_TICKS`, `STEPUP_CREST_REACH_SQ`, `RAM_JITTER_RECOVER_TICKS`, `RAM_RECOVER_DEBOUNCE`,
  `PAD_RAM_STALL_TICKS`. (`DIAGUP_PILLAR_TICKS:294` references `STEPUP_FREEZE_TICKS`, which STAYS — do not
  remove that.)

- [ ] **Step 5: run GREEN** — full suite, DELETE flags gone:
  `./gradlew :neoforge:runGameTestServer`. Expected: `required tests passed` (ignore known-flaky lottery
  arenas only if they are the sole failures). Confirm `stepUpCrestOrbitArena` green on both legs.

- [ ] **Step 6: build all loaders + commit** — `./gradlew build`, then
  `git commit -m "task#82 Unit5: CONFIRM subsumes crest orbit + delete unvalidated ascent flags (after task#83)"`.

---

### Task 6 (Unit 6): live A/B + default-ON flip

> **LIVE task, NOT an arena task.** The arena/replay suite is a regression guard; the flip gate is a
> clean LIVE A/B (spec §6.3, §8.2). Requires the refreshed task#83 baseline (Unit 5 blocker) already
> landed. Follow the memory rules: open `live-screen-watch` during experiments; pause monitor while
> editing code; survival tools are non-blocking (no long `awaitMs`).

Capture/replay the task#82 stall, resolve the §8.1 KEEP-redundancy open question, then flip the flag ON
and retire the now-dormant legacy ascent recovery gates.

**Files:**
- Modify (final): `common/src/main/java/net/magicterra/worlddriver/bot/BotConfig.java`
  (`walkerAscendMovement = true`)
- (Later, after proof) Modify: `common/.../bot/movement/Walker.java` (retire dormant legacy ascent
  recovery gates — scope finalized by Step 2's result)

**Interfaces:** none new — this unit flips a default and prunes now-dead legacy paths.

**Steps:**

- [ ] **Step 1: capture + replay the task#82 stall (OFF vs ON)** — reproduce the live dead-zone (a
  no-tool bot `goto` up a `stairUpBreak`/`stepUp` tail, ~1.2–1.5 b short). Record with
  `PathArchiveRecorder` (`bot/debug/PathArchiveRecorder.java`), then replay OFF vs ON via
  `mc.debug.replay` (`ReplayTool.java`). Confirm the ON run declares `UNREACHABLE` and re-routes with
  ZERO churn where OFF loops. Keep `live-screen-watch` on to catch any visual thrash the metrics miss.

- [ ] **Step 2: resolve the §8.1 KEEP-redundancy open question** (PREREQUISITE for finalizing the
  absorbed-logic list, spec §8.1 — surface it as an explicit decision, not a silent assumption). On the
  captured ascent-stall corpus, run a live A/B toggling each of `ascentRamSlide` (`Walker.java:928`),
  `arcWedge` (`1019`, ON), and `aboveNodeStall` (`1034`, ON) in ISOLATION to learn which the machine
  must faithfully absorb vs which can also retire. Record the verdict here (which are load-bearing).
  Do NOT delete any KEEP recovery on suspicion — this A/B is the only evidence that licenses retiring
  any of them.

- [ ] **Step 3: flip the default** — set `BotConfig.walkerAscendMovement = true` in `BotConfig.java`
  ONLY after Steps 1–2 pass on a clean live A/B against the refreshed baseline. Run the full gametest
  suite with the new default: `./gradlew :neoforge:runGameTestServer` — `required tests passed`.

- [ ] **Step 4: retire the now-dormant legacy ascent recovery gates** — only AFTER the flip proves out
  live, and only for the gates Step 2 convicted as shadowed by the machine (the load-bearing ones stay).
  Grep every `onInterrupt`/sibling reference before removing (memory: changing lifecycle semantics
  requires grepping all callers).

- [ ] **Step 5: build all loaders + commit** — `./gradlew build`, then
  `git commit -m "task#82 Unit6: default-ON walkerAscendMovement after live A/B; retire dormant ascent gates"`.
  Update Task / TODO.md / MEMORY.md to keep the three consistent (memory rule).

---

## Self-review

- **Spec §3–§6 coverage.** §3.1 interface/enum → Task 1 Steps 3–4. §3.2 `MovementContext` → Task 1
  Step 4. §3.3 delegation branch + `SUCCESS→step++` + `UNREACHABLE|FAILED→forceFellOffPath` → Task 1
  Steps 8–9. §4.1 PREP/ALIGN (pivot + square-up + chainAscend) → Task 2 Step 3; §4.1 bounded timeout →
  Task 4 Step 3. §4.2 BREAK (srcUp2/to/head + anti-suffocation dig + dig budget) → Task 3 Step 3. §4.3
  ASCEND (jump-timing + pillar-recover) → Task 2 Step 3 + Task 3 Step 5. §4.4 CONFIRM crest coverage →
  Task 5 Step 1. §5 KEEP migrate / DELETE remove → Tasks 2–4 (KEEP) + Task 5 (DELETE). §5 coexistence
  flag → Task 1 Steps 6, 9. §6.1 RED→GREEN dead-zone arena (two-leg beginReplay + adoptForTest companion)
  → Task 4 Steps 1, 4. §6.2 regression guards + registration → Tasks 2–4 arena sweeps + `gtOnlySkips`
  guards. §6.3 live A/B is the flip gate → Task 6.
- **Dependencies.** Unit 5 blocked on task#83 (Task 5 Step 0 gate). Unit 6 is a live task; §8.1
  KEEP-redundancy A/B is an explicit prerequisite step (Task 6 Step 2) before finalizing the absorbed
  list / retiring gates.
- **Placeholder scan.** No "TBD"/"similar to Task N"/"add error handling". Every test step gives a
  concrete `AGENT_GT_ONLY=…` command + expected FAIL/PASS; every code step gives real code or a precise
  edit with the anchor line + surrounding context.
- **Type/name consistency.** `MovementStatus` (PREP/RUNNING/SUCCESS/UNREACHABLE/FAILED),
  `AscendMovement`, `MovementContext`, `walkerAscendMovement`, `isMigratedAscent`, `forceFellOffPath` —
  identical across all tasks.
- **Code-vs-spec notes recorded inline:** (a) the pillar-recover sub-state cannot be "moved off Walker"
  without breaking byte-identical OFF → AscendMovement holds a parallel copy (Task 3 Step 5); (b) the
  within/passed advance at `Walker.java:2284` needs a flag-ON double-advance guard (Task 1 note 1 / Task 2
  Step 4); (c) `walkerChainMount` is declared **default `false`** ("Default OFF", `BotConfig.java:1817`),
  contradicting the spec §5 KEEP table's "ON" — the `chainAscend` port stays gated on the flag at its
  actual default, so no behavior changes vs today (Task 2 Step 3 ports it verbatim, flag-gated).
