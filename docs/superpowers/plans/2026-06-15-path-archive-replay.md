# Path Archive / Replay / Analysis Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Record every `goto` session to a self-contained JSON archive, deterministically replay the stored plan through the real Walker, and analyze each step (pose-fit, blocks, collision, fall, jump, deviation) with a standalone script — so pathfinding wedges reproduce and root-cause on demand.

**Architecture:** A second `PathTrace` sink (`PathArchiveRecorder`) captures seed/dimension/segments(path+edges)/sparse-block-envelope/per-tick-trajectory and writes JSON under `config/worlddriver/replays/`. A `mc.debug.replay` tool restores the envelope, teleports to start, and runs the Walker in a new `replayMode` (repath disabled) over the stored plan. `path-replay/analyze.py` reads the mod-precomputed physics booleans and prints a per-step report.

**Tech Stack:** Java 21, Architectury (NeoForge GameTest as the integration suite), the hand-written `JsonCodec`, MC 1.21.1 `Level`/`VoxelShape`/`Pose`, Python 3 + pytest for the analyzer.

**Spec:** `docs/superpowers/specs/2026-06-15-path-archive-replay-design.md`

**Conventions (AGENTS.md):** behavior goes in `DriverApi.route`, never a transport; world writes bounce through `server.execute()`; new MCP tool ⇒ route + `ToolCatalog` schema + an `agent_validation` parity script; keep `:neoforge:runGameTestServer` green (≥60 cases). Commit messages via `git commit -F -` heredoc; never override git config. There is an unrelated uncommitted `Walker.java` change in the tree (the prior `deepDescendCatchup` WIP) — **do not** sweep it into any commit here; always `git add` exact files.

---

## File Structure

**New files**
- `common/src/main/java/net/magicterra/worlddriver/bot/debug/NodePhysics.java` — pure helper: per-node pose-fit / hazard / fall / jump facts from a `Level`.
- `common/src/main/java/net/magicterra/worlddriver/bot/debug/PathArchive.java` — the archive data model + `JsonCodec`-backed read/write.
- `common/src/main/java/net/magicterra/worlddriver/bot/debug/PathArchiveRecorder.java` — the `PathTrace` capture sink.
- `common/src/main/java/net/magicterra/worlddriver/bot/debug/ReplayTool.java` — `mc.debug.replay` impl.
- `path-replay/analyze.py` + `path-replay/README.md` + `path-replay/sample-archive.json` + `path-replay/test_analyze.py`.

**Modified files**
- `bot/pathfinder/PathTrace.java` — `WalkerSample` gains `pose`, `aabbOverlap`; update `NOOP`.
- `bot/debug/PathSession.java` — `PlannedRoute` gains `edges`.
- `bot/debug/PathDebugRecorder.java` — pass the two new sample fields + `edges`.
- `bot/movement/Walker.java` — emit `pose`/`aabbOverlap`; widen the sample gate; `replayMode` + `beginReplay`; gate repath sites.
- `bot/BotConfig.java` — `pathArchive` flag (persisted by the existing reflection saver).
- `api/WorldApi.java` — extract a public `restoreCells(...)` helper the replayer reuses.
- `bot/debug/PathDebugBootstrap.java` — install the second sink + register `mc.debug.replay`.
- `mcp/catalog/DebugTools.java` — `mc.debug.replay` schema.
- `neoforge/.../AgentGameTest.java` — record→replay round-trip GameTest.
- `common/src/main/resources/data/worlddriver/scripts/agent_validation/` — a parity script for `mc.debug.replay`.

---

## Shared type signatures (used across tasks — keep identical)

```java
// PathTrace.WalkerSample (Task 1)
record WalkerSample(long tick, double x, double y, double z, float yawActual,
                    double targetX, double targetZ, int stepIndex, String moveType,
                    boolean onGround, boolean inWater,
                    String pose, boolean aabbOverlap) {}

// PathSession.PlannedRoute (Task 2)
public record PlannedRoute(List<BlockPos> path, List<Move.Edge> edges, boolean goalReached,
                           int repathIndex, int expanded, long ms, double finalCost) {}

// NodePhysics.Facts (Task 4)
public record Facts(boolean fitStand, boolean fitCrouch, boolean fitCrawl,
                    boolean collidesStanding, String ceilingForces,   // none|crouch|crawl|suffocate
                    boolean inWaterFoot, boolean submergedEye,
                    boolean underfootSolid, String footHazard,        // null | "lava" | ...
                    double fallFromPrev, boolean fallSurvivable,
                    boolean jumpNeeded, boolean jumpFeasible) {}
```

---

## Phase 1 — Capture (archive)

### Task 1: Extend `WalkerSample` with `pose` + `aabbOverlap`

**Files:**
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/pathfinder/PathTrace.java:31-33`
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/movement/Walker.java:2748-2760`

- [ ] **Step 1: Widen the record**

In `PathTrace.java`, replace the record declaration (lines 31-33):

```java
    record WalkerSample(long tick, double x, double y, double z, float yawActual,
                        double targetX, double targetZ, int stepIndex, String moveType,
                        boolean onGround, boolean inWater,
                        String pose, boolean aabbOverlap) {}
```

- [ ] **Step 2: Emit the new fields from Walker**

In `Walker.java` `sampleTick(Player p)`, widen the capture gate and fill the fields. Replace line 2748:

```java
        if (!BotConfig.pathDebug && !BotConfig.pathArchive) return;
```

Replace the `onWalkerTick` call (lines 2758-2760):

```java
        boolean overlap = !p.level().noCollision(p, p.getBoundingBox().deflate(1.0E-7));
        PathTraceHolder.SINK.onWalkerTick(new PathTrace.WalkerSample(
                p.tickCount, p.getX(), p.getY(), p.getZ(), p.getYRot(),
                tx, tz, step, mv, p.onGround(), p.isInWater(),
                p.getPose().name(), overlap));
```

- [ ] **Step 3: Fix the two non-Walker `WalkerSample` constructions**

Find every other `new PathTrace.WalkerSample(` / `new WalkerSample(`:

Run: `grep -rn "WalkerSample(" common/ neoforge/ fabric/ | grep new`

For each (the renderer/test fixtures), append `, "STANDING", false` to the argument list so it compiles. Expected sites: `PathChartRenderer`/test data builders if any.

- [ ] **Step 4: Compile**

Run: `./gradlew :common:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/net/magicterra/worlddriver/bot/pathfinder/PathTrace.java \
        common/src/main/java/net/magicterra/worlddriver/bot/movement/Walker.java
git commit -F - <<'EOF'
feat(debug): capture pose + aabbOverlap in WalkerSample

Widens the per-tick trajectory sample for the path-archive feature; the
sample gate now also fires when pathArchive is on (not just pathDebug).
EOF
```

---

### Task 2: Add `edges` to `PlannedRoute`

**Files:**
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/debug/PathSession.java:27`
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/debug/PathDebugRecorder.java:81-90`

- [ ] **Step 1: Widen the record**

In `PathSession.java`, replace the `PlannedRoute` record (line 27):

```java
    public record PlannedRoute(List<BlockPos> path, List<Move.Edge> edges, boolean goalReached,
                               int repathIndex, int expanded, long ms, double finalCost) {}
```

Add the import if missing: `import net.magicterra.worlddriver.bot.pathfinder.Move;`

- [ ] **Step 2: Pass edges at the construction site**

In `PathDebugRecorder.onSearchResult` (line 85), the method already receives `List<Move.Edge> edges`. Update the `new PathSession.PlannedRoute(...)` call to pass `edges` as the second argument (matching the new record order).

- [ ] **Step 3: Compile**

Run: `./gradlew :common:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add common/src/main/java/net/magicterra/worlddriver/bot/debug/PathSession.java \
        common/src/main/java/net/magicterra/worlddriver/bot/debug/PathDebugRecorder.java
git commit -F - <<'EOF'
feat(debug): carry edges in PlannedRoute

The archive needs the per-edge move type + break/place lists, not just the
node list, to replay the exact plan.
EOF
```

---

### Task 3: Add the `pathArchive` setting

**Files:**
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/BotConfig.java` (near line 52, with the other `autoX` flags)
- Modify: the `mc.bot.setting` schema/catalog if it enumerates boolean keys (check `grep -rn "pathDebug" common/.../mcp/catalog/ common/.../bot/BotApiImpl.java`)

- [ ] **Step 1: Add the field**

In `BotConfig.java`, beside the other `public static volatile boolean` flags:

```java
    /** When on, every goto session writes a JSON archive (seed, dimension, each
     *  progressive segment's path+edges, the sparse block envelope, and the
     *  per-tick trajectory) under config/worlddriver/replays/, for offline
     *  analysis and deterministic replay. Heavyweight → default OFF. */
    public static volatile boolean pathArchive = false;
```

The existing reflection-based saver/loader persists all scalar fields automatically (see the config-persistence memory), so no extra wiring is needed for persistence.

- [ ] **Step 2: Make sure `mc.bot.setting` accepts it**

Run: `grep -rn "pathDebug\|walkerDebug" common/src/main/java/net/magicterra/worlddriver/bot/BotApiImpl.java common/src/main/java/net/magicterra/worlddriver/mcp/catalog/`

If `pathDebug` is handled by a generic boolean-field reflection setter, `pathArchive` is picked up automatically — verify by reading the setter. If there is an explicit allow-list of setting keys, add `"pathArchive"` next to `"pathDebug"`.

- [ ] **Step 3: Compile**

Run: `./gradlew :common:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add common/src/main/java/net/magicterra/worlddriver/bot/BotConfig.java
git commit -F - <<'EOF'
feat(config): add pathArchive toggle (default off)
EOF
```

---

### Task 4: `NodePhysics` — per-node physics facts (TDD via GameTest)

**Files:**
- Create: `common/src/main/java/net/magicterra/worlddriver/bot/debug/NodePhysics.java`
- Test: `neoforge/src/main/java/net/magicterra/worlddriver/neoforge/AgentGameTest.java` (new `nodePhysicsArena`)

- [ ] **Step 1: Write the failing GameTest**

Add to `AgentGameTest.java` a test that builds a 1-block-ceiling pocket and a 2-deep drop, then asserts the `Facts`. Use the same registration style as the existing arenas (e.g. `descentArena`). Build at unused coords (e.g. cx=240,cz=240) and clear an air box first (per the arena-coord-collision lesson). Skeleton:

```java
@GameTest(template = "worlddriver:empty")  // match the template used by other arenas
public void nodePhysicsArena(GameTestHelper helper) {
    ServerLevel level = helper.getLevel();
    int x = 240, y = 180, z = 240;
    // clear, then: floor at y-1, a full 2-high standable cell at (x,y),
    // a 1-high gap at (x+2,y) capped by solid at y+1, lava at (x+4,y-1) under a stand cell,
    // and a 2-deep pit edge between (x,y) and (x,y+? )... (lay exact blocks here)
    BlockPos stand = new BlockPos(x, y, z);
    NodePhysics.Facts f = NodePhysics.compute(level, stand, null);
    helper.assertTrue(f.fitStand() && "none".equals(f.ceilingForces()), "open cell stands");
    BlockPos pocket = new BlockPos(x + 2, y, z);
    NodePhysics.Facts g = NodePhysics.compute(level, pocket, stand);
    helper.assertTrue("crouch".equals(g.ceilingForces()) || "crawl".equals(g.ceilingForces()),
            "1-cap pocket forces crouch/crawl");
    helper.succeed();
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `./gradlew :neoforge:runGameTestServer --tests '*nodePhysicsArena*' 2>&1 | grep -iE "nodePhysics|FAILED|compil"`
Expected: compile error `NodePhysics` not found (or test FAIL).

- [ ] **Step 3: Implement `NodePhysics`**

```java
package net.magicterra.worlddriver.bot.debug;

import net.magicterra.worlddriver.bot.BotConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** Pure, dependency-light per-node physics facts for the path archive. Uses the
 *  real {@link Level} collision so the analysis matches Minecraft exactly. */
public final class NodePhysics {
    private NodePhysics() {}

    // Player pose boxes (MC 1.21.1): width 0.6; heights stand 1.8 / crouch 1.5 / crawl 0.6.
    private static final EntityDimensions STAND  = EntityDimensions.scalable(0.6f, 1.8f);
    private static final EntityDimensions CROUCH = EntityDimensions.scalable(0.6f, 1.5f);
    private static final EntityDimensions CRAWL  = EntityDimensions.scalable(0.6f, 0.6f);
    private static final double MAX_UP_STEP = 0.6;   // auto-step; above this a node needs a jump
    private static final double JUMP_APEX   = 1.25;  // sprint-jump clears ~1.25 blocks

    public record Facts(boolean fitStand, boolean fitCrouch, boolean fitCrawl,
                        boolean collidesStanding, String ceilingForces,
                        boolean inWaterFoot, boolean submergedEye,
                        boolean underfootSolid, String footHazard,
                        double fallFromPrev, boolean fallSurvivable,
                        boolean jumpNeeded, boolean jumpFeasible) {}

    /** @param next the next planned node (for jump need/feasibility) or null. */
    public static Facts compute(Level level, BlockPos foot, BlockPos prev, BlockPos next) {
        boolean fitStand  = fits(level, STAND, foot);
        boolean fitCrouch = fits(level, CROUCH, foot);
        boolean fitCrawl  = fits(level, CRAWL, foot);
        String forces = fitStand ? "none" : fitCrouch ? "crouch" : fitCrawl ? "crawl" : "suffocate";

        boolean inWaterFoot = level.getFluidState(foot).is(FluidTags.WATER);
        boolean submergedEye = level.getFluidState(foot.above()).is(FluidTags.WATER);
        BlockState below = level.getBlockState(foot.below());
        boolean underfootSolid = below.isFaceSturdy(level, foot.below(), net.minecraft.core.Direction.UP);
        String hazard = hazard(level, foot);

        double fall = prev == null ? 0.0 : (prev.getY() - foot.getY());
        boolean fallOk = fall <= BotConfig.survivableFall;

        boolean jumpNeeded = false, jumpFeasible = true;
        if (next != null) {
            double dy = next.getY() - foot.getY();
            jumpNeeded = dy > MAX_UP_STEP;
            jumpFeasible = dy <= JUMP_APEX
                    && fits(level, CRAWL, foot.above())          // takeoff headroom
                    && (fitStand(level, next) || fitCrouchAt(level, next));   // landing fits
        }
        return new Facts(fitStand, fitCrouch, fitCrawl, !fitStand, forces,
                inWaterFoot, submergedEye, underfootSolid, hazard, fall, fallOk,
                jumpNeeded, jumpFeasible);
    }

    private static boolean fits(Level level, EntityDimensions dim, BlockPos foot) {
        AABB box = dim.makeBoundingBox(foot.getX() + 0.5, foot.getY(), foot.getZ() + 0.5).deflate(1.0E-7);
        return level.noCollision(box);
    }
    private static boolean fitStand(Level level, BlockPos foot)   { return fits(level, STAND, foot); }
    private static boolean fitCrouchAt(Level level, BlockPos foot){ return fits(level, CROUCH, foot); }

    private static String hazard(Level level, BlockPos foot) {
        for (BlockPos p : new BlockPos[]{foot, foot.below()}) {
            BlockState s = level.getBlockState(p);
            if (s.is(Blocks.LAVA)) return "lava";
            if (s.is(Blocks.MAGMA_BLOCK)) return "magma";
            if (s.is(Blocks.CACTUS)) return "cactus";
            if (s.is(Blocks.CAMPFIRE) || s.is(Blocks.SOUL_CAMPFIRE)) return "campfire";
            if (s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE)) return "fire";
            if (s.is(Blocks.SWEET_BERRY_BUSH)) return "sweet_berry";
            if (s.is(Blocks.WITHER_ROSE)) return "wither_rose";
            if (s.is(Blocks.POWDER_SNOW)) return "powder_snow";
        }
        return null;
    }
}
```

Update the GameTest call sites to `NodePhysics.compute(level, stand, null, null)` etc. (the test only checks `fit`/`ceilingForces`; pass `null` for prev/next where unused). If `BotConfig.survivableFall` does not exist under that exact name, run `grep -n "survivableFall\|survivable" common/src/main/java/net/magicterra/worlddriver/bot/BotConfig.java` and use the real field.

- [ ] **Step 4: Run the test to confirm it passes**

Run: `./gradlew :neoforge:runGameTestServer --tests '*nodePhysicsArena*' 2>&1 | grep -iE "nodePhysicsArena|PASS|FAIL"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/net/magicterra/worlddriver/bot/debug/NodePhysics.java \
        neoforge/src/main/java/net/magicterra/worlddriver/neoforge/AgentGameTest.java
git commit -F - <<'EOF'
feat(debug): NodePhysics — per-node pose-fit/hazard/fall/jump facts

Real Level collision (noCollision with the 1.8/1.5/0.6 pose boxes) so the
archive analysis matches Minecraft's own pose/suffocation rules.
EOF
```

---

### Task 5: `PathArchive` data model + JSON round-trip (TDD via GameTest)

**Files:**
- Create: `common/src/main/java/net/magicterra/worlddriver/bot/debug/PathArchive.java`
- Test: new `pathArchiveJsonArena` in `AgentGameTest.java`

- [ ] **Step 1: Write the failing round-trip GameTest**

```java
@GameTest(template = "worlddriver:empty")
public void pathArchiveJsonArena(GameTestHelper helper) {
    PathArchive a = PathArchive.demo();           // a tiny fixed archive (Step 3 adds it)
    String json = a.toJson();
    PathArchive b = PathArchive.fromJson(json);
    helper.assertTrue(b.header().seed() == a.header().seed()
            && b.segments().size() == a.segments().size()
            && b.trajectory().size() == a.trajectory().size(), "round-trips");
    helper.succeed();
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `./gradlew :neoforge:runGameTestServer --tests '*pathArchiveJsonArena*' 2>&1 | grep -iE "PathArchive|FAILED|compil"`
Expected: compile error `PathArchive` not found.

- [ ] **Step 3: Implement `PathArchive`**

Build nested records (`Header`, `Segment`, `NodeFacts`, `EnvelopeCell`, `Tick`) mirroring the §4 schema, plus `toJson()`/`fromJson(String)` using `JsonCodec.encode`/`decode` over plain `Map`/`List` (the codec already handles `BlockPos`, numbers, strings, null). Provide `static PathArchive demo()` returning a one-segment, one-tick fixed instance for the test. Convert records→`Map` in `toJson` and `Map`→records in `fromJson`. Reuse `NodePhysics.Facts` for the node facts (store its fields as a map).

Key methods:

```java
public String toJson() { return JsonCodec.encode(toMap()); }
public static PathArchive fromJson(String s) { return fromMap((Map<String,Object>) JsonCodec.decode(s)); }
```

(Write `toMap()`/`fromMap(...)` explicitly — no reflection — so the on-disk shape is stable and matches `analyze.py`.)

- [ ] **Step 4: Run the test to confirm it passes**

Run: `./gradlew :neoforge:runGameTestServer --tests '*pathArchiveJsonArena*' 2>&1 | grep -iE "pathArchiveJsonArena|PASS|FAIL"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/net/magicterra/worlddriver/bot/debug/PathArchive.java \
        neoforge/src/main/java/net/magicterra/worlddriver/neoforge/AgentGameTest.java
git commit -F - <<'EOF'
feat(debug): PathArchive model + JsonCodec round-trip
EOF
```

---

### Task 6: `PathArchiveRecorder` (capture sink) + envelope sampling

**Files:**
- Create: `common/src/main/java/net/magicterra/worlddriver/bot/debug/PathArchiveRecorder.java`

- [ ] **Step 1: Implement the sink**

Implement `PathTrace`. Hold a mutable in-progress session (header + `List<Segment>` + dedup `Long2ObjectMap` envelope + `List<Tick>`). Behavior:
- `onSearchBegin(start, goal)`: if `!BotConfig.pathArchive` ignore; else start a session — capture `start`, `goalDesc`, and resolve seed+dimension from the server (Step 2).
- `onSearchResult(path, edges, …)`: append a `Segment`; for each node `i` compute `NodePhysics.compute(level, path.get(i), path.get(i-1)|null, path.get(i+1)|null)` and sample the envelope cells `(x±2, y∈[-1,+2], z±2)` via the server level (dedup by `BlockPos.asLong()`), recording `block`, `solid`, `shape` (only when not full/empty), `fluid`. **Read on the server thread** — guard with the project's existing `server.execute(...)` / snapshot helper (mirror `WorldApi.snapshot`).
- `onWalkerTick(sample)`: append a `Tick` (carry the new `pose`/`aabbOverlap`; compute `deviation=NaN` for a plan archive).
- `onTerminal(outcome, reason)`: finalize header, write JSON via a background thread to `config/worlddriver/replays/replay-<counter>-<epochMs>.json` (reuse the `PathChartWriter` counter/dir pattern; new sibling writer or a small shared helper), clear the session.

- [ ] **Step 2: Resolve seed + dimension**

Get the integrated server from the project's server accessor (the same one `server.execute` uses; `grep -rn "getServer()\|MinecraftServer\|integratedServer\|server.execute" common/src/main/java/net/magicterra/worlddriver/api/` to find it). `seed = server.overworld().getSeed()` (or `getWorldData().worldGenOptions().seed()`); `dimension = level.dimension().location().toString()`. If no integrated server (dedicated), store `seed = null`.

- [ ] **Step 3: Compile**

Run: `./gradlew :common:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add common/src/main/java/net/magicterra/worlddriver/bot/debug/PathArchiveRecorder.java
git commit -F - <<'EOF'
feat(debug): PathArchiveRecorder — capture goto sessions to JSON
EOF
```

---

### Task 7: Install the second sink + end-to-end capture GameTest

**Files:**
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/debug/PathDebugBootstrap.java:26-30`
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/pathfinder/PathTraceHolder.java` (if `SINK` is a single field, add fan-out)
- Test: new `pathArchiveCaptureArena` in `AgentGameTest.java`

- [ ] **Step 1: Fan out to both sinks**

`PathTraceHolder.SINK` is a single sink today. Add a tiny `MultiTrace implements PathTrace` that forwards to a list, and in `PathDebugBootstrap.init` set `PathTraceHolder.SINK = new MultiTrace(recorder, archiveRecorder)`. (Or add a second holder field consulted by the Walker emit — prefer `MultiTrace` so the Walker keeps one call site.)

```java
PathDebugRecorder recorder = new PathDebugRecorder();
PathArchiveRecorder archive = new PathArchiveRecorder();
PathTraceHolder.SINK = new MultiTrace(recorder, archive);
PathChartTool.bind(recorder);
```

- [ ] **Step 2: Write the failing capture GameTest**

Drive a short real `goto` across a built arena with `BotConfig.pathArchive = true`, wait for arrival, then assert an archive file exists with ≥1 segment and a non-empty trajectory. Read the newest file in `config/worlddriver/replays/` and `PathArchive.fromJson` it. Model the goto-drive on an existing live-ish GameTest (e.g. the smoothness/`descentArena` arenas) — set `BotConfig.pathArchive=true` in setup and back to false in teardown.

- [ ] **Step 3: Run it to confirm it fails, then passes after Step 1**

Run: `./gradlew :neoforge:runGameTestServer --tests '*pathArchiveCaptureArena*' 2>&1 | grep -iE "pathArchiveCapture|PASS|FAIL"`
Expected: FAIL before the bootstrap wiring is complete; PASS after.

- [ ] **Step 4: Full suite stays green**

Run: `./gradlew :neoforge:runGameTestServer 2>&1 | grep -iE "All [0-9]+ required|TOTAL:|FAILED"`
Expected: `All N required tests passed` (N ≥ 62 now), `FAIL: 0`.

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/net/magicterra/worlddriver/bot/debug/PathDebugBootstrap.java \
        common/src/main/java/net/magicterra/worlddriver/bot/pathfinder/PathTraceHolder.java \
        neoforge/src/main/java/net/magicterra/worlddriver/neoforge/AgentGameTest.java
git commit -F - <<'EOF'
feat(debug): install PathArchiveRecorder sink + capture round-trip test
EOF
```

---

## Phase 2 — Replay

### Task 8: Walker `replayMode` + `beginReplay`

**Files:**
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/movement/Walker.java`

- [ ] **Step 1: Add the field + entry point**

Add field next to `replayMode`'s neighbours (with the other booleans ~line 273):

```java
    private boolean replayMode;   // executing a fixed archived plan: no repath/quick-start/splice
```

Add a public entry that injects a fixed path and disables planning:

```java
    /** Replay a fixed archived plan: caller has already teleported the bot to the
     *  plan start and restored the block envelope. Bypasses A* entirely. */
    public void beginReplay(List<BlockPos> plan, List<Move.Edge> planEdges, Goal endGoal) {
        setGoal(endGoal);                 // resets all per-goal state
        this.replayMode = true;
        // Anchor at the bot's foot (≈ plan start after the teleport) and adopt.
        BlockPos foot = /* current foot from the avatar — mirror how tick() derives foot */;
        adoptPath(new PathFinder.Result(plan, planEdges, true, 0, 0L, 0.0), null, foot);
    }
```

(For the `foot` derivation, copy the one-liner `tick()` uses at its top — `grep -n "BlockPos foot ="` in Walker.java — and pass `world=null` only if `adoptPath` tolerates it; otherwise thread the `WorldView` through `beginReplay`. Prefer threading `WorldView world` in as a parameter so `adoptPath`'s stringPull runs identically to a live adopt.)

- [ ] **Step 2: Gate every repath kickoff with `!replayMode`**

At each site, wrap the `activeSearch = new PathFinder(...).newSearch(...)` (and the `tryQuickStart`/`tryWaterBeeline` kickoff) so they are skipped in replay. Sites (verify line numbers with `grep -n "new PathFinder(world).newSearch\|tryQuickStart(world"`):

- Walker.java:748, 756, 894, 947, 1266/1267/1274, 2486/2487.

Pattern at the `safetyRepath` decision (~line 703): change
`if ((safetyRepath || fullPeriodic) && activeSearch == null) {`
to
`if (!replayMode && (safetyRepath || fullPeriodic) && activeSearch == null) {`
and similarly guard the `if (path == null)` quick-start block (~946) with `if (!replayMode && path == null)`.

When `replayMode` and the bot wedges (no progress), do NOT repath — let `noStepProgressTicks` climb and let the normal terminal/stuck path fire so the replay ends (recording the wedge). Confirm the terminal-on-stuck path is reachable without a repath (it is: the total-tick / stuck-tick budgets call `terminal(...)`).

- [ ] **Step 3: Compile + suite green**

Run: `./gradlew :neoforge:runGameTestServer 2>&1 | grep -iE "All [0-9]+ required|FAILED"`
Expected: still green (no replay caller yet — this only adds a dormant mode).

- [ ] **Step 4: Commit**

```bash
git add common/src/main/java/net/magicterra/worlddriver/bot/movement/Walker.java
git commit -F - <<'EOF'
feat(walker): replayMode + beginReplay (fixed-plan execution, no repath)
EOF
```

---

### Task 9: Extract a shared `restoreCells` helper in `WorldApi`

**Files:**
- Modify: `common/src/main/java/net/magicterra/worlddriver/api/WorldApi.java:145-170`

- [ ] **Step 1: Extract**

Pull the block-write loop (the restore inner body around lines 149-165: `level.setBlockAndUpdate(cur, state)` + BE `loadWithComponents`) into a reusable package-visible/public static helper that takes a list of `(BlockPos, BlockState, CompoundTag|null)` and applies them on the server thread:

```java
public static void restoreCells(ServerLevel level, List<Cell> cells) { /* moved loop */ }
public record Cell(BlockPos pos, BlockState state, CompoundTag beTag) {}
```

Have `restore(id,…)` build `List<Cell>` from its snapshot and call `restoreCells`. Keep behavior identical (no functional change to `mc.world.restore`).

- [ ] **Step 2: Compile**

Run: `./gradlew :common:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Confirm `mc.world.restore` unchanged**

Run: `./gradlew :neoforge:runGameTestServer --tests '*orld*' 2>&1 | grep -iE "PASS|FAIL"` (or the existing snapshot/restore test name).
Expected: unchanged PASS.

- [ ] **Step 4: Commit**

```bash
git add common/src/main/java/net/magicterra/worlddriver/api/WorldApi.java
git commit -F - <<'EOF'
refactor(world): extract restoreCells helper for reuse by the replayer
EOF
```

---

### Task 10: `ReplayTool` + `mc.debug.replay` route & schema

**Files:**
- Create: `common/src/main/java/net/magicterra/worlddriver/bot/debug/ReplayTool.java`
- Modify: `common/src/main/java/net/magicterra/worlddriver/mcp/catalog/DebugTools.java`
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/debug/PathDebugBootstrap.java:33`

- [ ] **Step 1: Implement `ReplayTool.replay(Map<String,Object> p)`**

```
1. file = p.get("file") or the newest config/worlddriver/replays/replay-*.json (not replay-run-*).
2. PathArchive a = PathArchive.fromJson(read(file)).
3. server.execute(() -> {
     if (restoreBlocks!=false) WorldApi.restoreCells(level, a.envelope()->Cells);
     runCommand("/tp @s <start.x+0.5> <start.y> <start.z+0.5>");
     List<BlockPos> plan = concat(segments.path); List<Move.Edge> edges = concat(segments.edges);
     walker.beginReplay(plan, edges, new Goal.Block(lastNode));   // bot.walker accessor
   });
4. The PathArchiveRecorder (already installed) captures the replay run; tag its kind="replay",
   planRef=file, and compute per-tick deviation = nearest planned-node distance.
5. return {ok:true, file:<written replay-run path or "pending">, segments, plannedNodes}.
```

For deviation: extend `PathArchiveRecorder` so that when `replayMode` is active it knows the plan node list (pass it in via a `beginReplayCapture(planNodes)` call from `ReplayTool` before `beginReplay`), and fills `Tick.deviation`. Write the replay run as `replay-run-<counter>-<epochMs>.json` with `kind:"replay"`.

Find the `walker` + `server`/`level` accessors: `grep -rn "Walker walker\|\.walker\b\|server.execute\|ServerLevel" common/src/main/java/net/magicterra/worlddriver/bot/BotApiImpl.java | head`.

- [ ] **Step 2: Schema in `DebugTools.java`**

```java
roTool("mc.debug.replay",
    "Replay a recorded goto archive: restore its block envelope, teleport the bot " +
    "to the recorded start, and re-execute the stored plan through the Walker " +
    "(no re-planning) so wedges reproduce. Writes a replay-run-*.json with the " +
    "actual trajectory + per-step deviation.",
    object()
        .prop("file", string().desc("Archive filename under config/worlddriver/replays/. Default: newest plan archive."))
        .prop("restoreBlocks", bool().desc("Restore the recorded block envelope before replay. Default true."))
        .prop("fromStep", integer(0, 100000).desc("Start at this plan step. Default 0.")))
```

- [ ] **Step 3: Register the route**

In `PathDebugBootstrap.init` next to the other `api.addRoute(...)`:

```java
api.addRoute("mc.debug.replay", ReplayTool::replay);
```

- [ ] **Step 4: Compile + suite green**

Run: `./gradlew :neoforge:runGameTestServer 2>&1 | grep -iE "All [0-9]+ required|FAILED"`
Expected: green.

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/net/magicterra/worlddriver/bot/debug/ReplayTool.java \
        common/src/main/java/net/magicterra/worlddriver/mcp/catalog/DebugTools.java \
        common/src/main/java/net/magicterra/worlddriver/bot/debug/PathDebugBootstrap.java
git commit -F - <<'EOF'
feat(debug): mc.debug.replay — restore envelope, tp, re-execute stored plan
EOF
```

---

### Task 11: Replay round-trip GameTest + transport parity

**Files:**
- Test: new `replayRoundTripArena` in `AgentGameTest.java`
- Create: `common/src/main/resources/data/worlddriver/scripts/agent_validation/NN_replay_parity.js`

- [ ] **Step 1: Write the round-trip GameTest**

`pathArchive=true` → `goto` across the arena to a known cell → capture the archive → `ReplayTool.replay({file:<that archive>})` → wait for terminal → assert the bot's final cell equals the recorded goal cell (±1) and that a `replay-run-*.json` was written whose max `deviation` ≤ a bound (e.g. 3.0). Reuse the arena from Task 7.

- [ ] **Step 2: Run it**

Run: `./gradlew :neoforge:runGameTestServer --tests '*replayRoundTripArena*' 2>&1 | grep -iE "replayRoundTrip|PASS|FAIL"`
Expected: PASS.

- [ ] **Step 3: Transport parity script**

Following `07_mcp_parity.js`/`06_rpc_parity.js`, add a script that calls `mc.debug.replay` through all three transports on the same fixed archive and asserts byte-identical results. (For determinism, assert on the returned metadata shape, not on live trajectory bytes — e.g. `ok`, `segments`, `plannedNodes`.) Number it after the highest existing `NN_`.

- [ ] **Step 4: Full suite green**

Run: `./gradlew :neoforge:runGameTestServer 2>&1 | grep -iE "All [0-9]+ required|TOTAL:|FAILED"`
Expected: `All N required tests passed`, `FAIL: 0`.

- [ ] **Step 5: Commit**

```bash
git add neoforge/src/main/java/net/magicterra/worlddriver/neoforge/AgentGameTest.java \
        common/src/main/resources/data/worlddriver/scripts/agent_validation/
git commit -F - <<'EOF'
test(debug): replay round-trip GameTest + mc.debug.replay transport parity
EOF
```

---

## Phase 3 — Analysis script

### Task 12: `analyze.py` skeleton + loader (TDD, pytest)

**Files:**
- Create: `path-replay/analyze.py`, `path-replay/sample-archive.json`, `path-replay/test_analyze.py`

- [ ] **Step 1: Commit a tiny fixed sample archive**

Create `path-replay/sample-archive.json` by hand — a 1-segment, 3-node plan with `nodes[]` facts (one node with `ceilingForces:"suffocate"`, one with `footHazard:"lava"`, one with `jumpToNext:{needed:true,feasible:false}`), `edges[]` where one edge has a non-empty `break:[{x,y,z}]` and another a non-empty `place:[{x,y,z}]`, and a 3-tick trajectory (one tick `pose:"STANDING"` at the suffocate node). This is the golden input.

- [ ] **Step 2: Write the failing test**

```python
# path-replay/test_analyze.py
import json, subprocess, sys, pathlib
HERE = pathlib.Path(__file__).parent

def run(*args):
    return subprocess.run([sys.executable, str(HERE/"analyze.py"), *args],
                          capture_output=True, text=True)

def test_flags_suffocation_and_hazard():
    r = run(str(HERE/"sample-archive.json"), "--all")
    assert r.returncode == 0, r.stderr
    out = r.stdout
    assert "suffocate" in out
    assert "lava" in out
    assert "jump" in out.lower()
```

- [ ] **Step 3: Run it to confirm it fails**

Run: `./.venv/bin/python -m pytest path-replay/test_analyze.py -q`
Expected: FAIL (analyze.py missing / no output).

- [ ] **Step 4: Implement the loader + `--all`/`--step` arg parsing**

`analyze.py`: argparse (`archive`, `--step`, `--all`, `--replay`, `--deviation-threshold` default 1.5). Load JSON, flatten `segments[].nodes[]` into a step list, print a header + one line per step with the node facts (raw, no recompute). Just enough to make the test pass.

- [ ] **Step 5: Run it to confirm it passes**

Run: `./.venv/bin/python -m pytest path-replay/test_analyze.py -q`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add path-replay/analyze.py path-replay/sample-archive.json path-replay/test_analyze.py
git commit -F - <<'EOF'
feat(analyze): path-replay/analyze.py loader + golden-sample test
EOF
```

---

### Task 13: Per-step table, anomaly flags, deviation

**Files:**
- Modify: `path-replay/analyze.py`, `path-replay/test_analyze.py`

- [ ] **Step 1: Add a failing test for the table + deviation**

```python
def test_table_columns_and_deviation():
    r = run(str(HERE/"sample-archive.json"), "--all")
    out = r.stdout
    for col in ("step", "pose", "fit", "underfoot", "fall", "jump", "break", "place"):
        assert col in out.lower()

def test_marks_planned_block_ops():
    # sample-archive has one edge with a break[] and one with a place[] (add them in Task 12 fixture)
    r = run(str(HERE/"sample-archive.json"), "--all")
    out = r.stdout.upper()
    assert "BREAK" in out and "PLACE" in out

def test_replay_deviation(tmp_path):
    # a tiny replay-run with one tick deviation 4.0 → flagged over the 1.5 threshold
    ...   # build a minimal replay-run.json, run with --replay, assert "4.0" and "DRIFT" appear
```

> **Task 12 fixture note:** when authoring `sample-archive.json`, give one edge a
> non-empty `break:[{x,y,z}]` and another a non-empty `place:[{x,y,z}]` so this test
> has real data to surface.

- [ ] **Step 2: Run to confirm fail**

Run: `./.venv/bin/python -m pytest path-replay/test_analyze.py -q`
Expected: the two new tests FAIL.

- [ ] **Step 3: Implement**

Render an aligned table; for each step map plan→nearest trajectory tick (by step index / position) to show the **executed** pose beside `ceilingForces`; include `BREAK`/`PLACE` columns sourced from `segments[].edges[i].break`/`.place` (show the cell + count, blank when none); flag rows: `SUFFOCATE` (ceilingForces=suffocate or executed pose can't-fit), `COLLIDE` (collidesStanding/aabbOverlap), `HAZARD` (footHazard≠null), `FALL!` (not fallSurvivable), `JUMP✗` (jumpNeeded & !feasible), `DRIFT` (deviation > threshold). With `--replay`, read the replay-run, compute/echo per-step deviation, and print a summary (max/mean, count over threshold). `--step N` prints just that step verbosely (full break/place cell lists). The planner's block ops thus get marked per step, per the spec.

- [ ] **Step 4: Run to confirm pass**

Run: `./.venv/bin/python -m pytest path-replay/test_analyze.py -q`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add path-replay/analyze.py path-replay/test_analyze.py
git commit -F - <<'EOF'
feat(analyze): per-step table, anomaly flags, replay deviation summary
EOF
```

---

### Task 14: README + CHANGELOG

**Files:**
- Create: `path-replay/README.md`
- Modify: `CHANGELOG.md` (`[Unreleased]`)

- [ ] **Step 1: Write `path-replay/README.md`**

Document: turning on capture (`mc.bot.setting {pathArchive:true}`), where archives land (`config/worlddriver/replays/`), replaying (`mc.debug.replay {file}`), and analyzing (`./.venv/bin/python path-replay/analyze.py <archive.json> --all` / `--step N` / `--replay <run.json>`). Note the archive schema version and the suffocation/hazard/jump/drift flags.

- [ ] **Step 2: CHANGELOG entry**

Add an `[Unreleased]` bullet describing the path archive/replay/analysis toolchain and the new `mc.debug.replay` tool + `pathArchive` setting.

- [ ] **Step 3: Commit**

```bash
git add path-replay/README.md CHANGELOG.md
git commit -F - <<'EOF'
docs: path-replay README + CHANGELOG entry
EOF
```

---

## Final verification

- [ ] `./gradlew build` — full build green.
- [ ] `./gradlew :neoforge:runGameTestServer 2>&1 | grep -iE "All [0-9]+ required|TOTAL:"` — `All N required tests passed`, `FAIL: 0`.
- [ ] `./.venv/bin/python -m pytest path-replay/ -q` — all green.
- [ ] **Live smoke** (optional but recommended): in the running client, `mc.bot.setting {pathArchive:true}`, run one real `goto`, confirm a `replay-*.json` lands, `mc.debug.replay` it, then `analyze.py` the archive and eyeball the per-step report against the known wedge (e.g. the canyon water chamber).

---

## Self-Review

**Spec coverage:** seed/dimension/segments/start/goal/envelope (Tasks 5,6) ✓; replay tp+restore+re-execute (Tasks 8,9,10) ✓; analysis pose/self/underfoot/collision/fall/jump/deviation (Tasks 4,12,13) ✓; **planned break/place marking** (edges captured Task 2; surfaced in the analysis Task 13) ✓; toggle (Task 3) ✓; mod-precomputed booleans (Task 4) ✓; ground-truth pose (Task 1) ✓; GameTest round-trip + parity (Tasks 7,11) ✓.

**Placeholders:** the two spots marked "lay exact blocks here" (Task 4 arena) and "build a minimal replay-run.json" (Task 13) are test-fixture authoring left to the engineer with the exact assertions specified — acceptable, but the engineer must fill concrete blocks/JSON, not stubs.

**Type consistency:** `WalkerSample(+pose,+aabbOverlap)`, `PlannedRoute(+edges)`, `NodePhysics.Facts`, `PathArchive.{Header,Segment,Tick}` are defined once in "Shared type signatures" and referenced unchanged. `NodePhysics.compute(level, foot, prev, next)` signature is consistent between Task 4 impl and its GameTest callers (note: the GameTest in Step 1 must use the 4-arg form).
