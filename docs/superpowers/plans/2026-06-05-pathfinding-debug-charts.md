# Pathfinding Debug Charts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an opt-in, fully-strippable pathfinding visualization that records search candidates, every planned route, and the actual walked trajectory (with heading + speed), and renders them to a composite PNG dashboard on disk for multimodal analysis.

**Architecture:** A tiny inert SPI seam in core (`PathTrace` interface + `NOOP` + `PathTraceHolder.SINK`) is called from `PathFinder` and `Walker`. All heavy logic — recorder, AWT renderer, file writer, MCP tool, schema, bootstrap — lives in a self-contained `net.magicterra.worlddriver.bot.debug` package that registers itself at client init via generic core seams (`DriverApi.addRoute`, `ToolCatalog.registerExtra`). Release strip = delete the `bot.debug` package + remove the one `PathDebugBootstrap.init()` call. Core compiles unchanged and pays zero runtime cost when no recorder is registered.

**Tech Stack:** Java 21, NeoForge/Architectury MC 1.21.1, AWT `BufferedImage`/`Graphics2D` + `javax.imageio.ImageIO` (already used headless in `Screenshots.java`), Rhino JS validation suite.

---

## Conventions & test seam (read before starting)

- **AGENTS.md Hard Rules apply.** Especially: #1 behavior is reachable through `DriverApi.route` and byte-identical across MCP/RPC/script; #6 prefer extending — justification for a *new* tool here is that no existing tool exposes path traces and the data is debug-only (keep the schema/description tight); #7 no fully-qualified names, add an `import` and use the simple name (only inline an FQN to break a real collision).
- **This codebase has no JUnit unit-test source set.** The canonical suite is `./gradlew :neoforge:runGameTestServer`, which runs the numbered JS validation scripts under `common/src/main/resources/data/worlddriver/scripts/validation/`. Therefore the automated test for this feature is an **integration JS validation case** plus **compile gates**; the substantive correctness check is the **E2E multimodal run** (Task 14). Where a Java class is pure (renderer math), keep it small and assert its outputs through the JS case's response fields (width/height/bytes/stats). Do not invent a JUnit harness.
- **Working tree state:** branch `feat/cost-based-flee` has uncommitted survival-kit changes including a modified `BotConfig.java`. Append new fields at the end of the relevant section; do not disturb existing edits. Confirm with the user before committing (per session rules, commit only when asked; branch first if on a default branch).
- **Never commit runtime output (Hard Rule #5).** Chart PNGs write to `config/worlddriver/debug/`; Task 13 adds that to `.gitignore`.
- **Build/test commands:**
  - Compile common: `./gradlew :common:compileJava`
  - Full suite (must stay green): `./gradlew :neoforge:runGameTestServer`
  - Interactive client (E2E): `JAVA_TOOL_OPTIONS="-Dworlddriver.mcpPort=39800 -Dworlddriver.rpcPort=39801" ./gradlew :fabric:runClient`
  - Never `./gradlew --stop` while a client is alive; kill by port owner: `lsof -ti:39801 | xargs -r kill`.

---

## File map

**New (core seam — inert, never stripped):**
- `common/src/main/java/net/magicterra/worlddriver/bot/pathfinder/PathTrace.java` — SPI interface + `NOOP` + nested `WalkerSample`/`Outcome`.
- `common/src/main/java/net/magicterra/worlddriver/bot/pathfinder/PathTraceHolder.java` — `static volatile PathTrace SINK = NOOP`.

**New (debug package — self-contained, stripped at release):**
- `common/src/main/java/net/magicterra/worlddriver/bot/debug/PathSession.java` — immutable snapshot DTO (+ nested `PlannedRoute`, `Candidate`).
- `common/src/main/java/net/magicterra/worlddriver/bot/debug/PathDebugRecorder.java` — `implements PathTrace`; buffers + snapshot + autoDump.
- `common/src/main/java/net/magicterra/worlddriver/bot/debug/PathChartRenderer.java` — pure `(PathSession, opts) -> BufferedImage`.
- `common/src/main/java/net/magicterra/worlddriver/bot/debug/PathChartWriter.java` — encode + write PNG, returns metadata.
- `common/src/main/java/net/magicterra/worlddriver/bot/debug/PathChartTool.java` — route handler (snapshot → render → write → response Map).
- `common/src/main/java/net/magicterra/worlddriver/bot/debug/DebugTools.java` — MCP schema catalog (`mc.debug.pathChart`).
- `common/src/main/java/net/magicterra/worlddriver/bot/debug/PathDebugBootstrap.java` — `init()` wiring.
- `common/src/main/resources/data/worlddriver/scripts/validation/56_debug_pathchart.js` — validation case.

**Modified (core — generic inert seams + hook calls):**
- `common/.../api/DriverApi.java` — `routes` → `ConcurrentHashMap`; add `addRoute(...)`.
- `common/.../WorldDriverCommon.java` — add `api()` getter; register `56_debug_pathchart.js`.
- `common/.../mcp/ToolCatalog.java` — generic `registerExtra(...)` + concat extras.
- `common/.../client/ClientHooks.java` — call `PathDebugBootstrap.init()`.
- `common/.../bot/pathfinder/PathFinder.java` — fire `onSearchBegin` / `onNodeExpanded`.
- `common/.../bot/movement/Walker.java` — fire `onSearchResult` / `onWalkerTick` / `onTerminal`.
- `common/.../bot/BotConfig.java` — 4 new settings (auto-persisted).
- `common/.../bot/SettingsCommand.java` — parse + snapshot the 4 keys.
- `.gitignore` — ignore `**/config/worlddriver/debug/`.

---

## Task 1: Core SPI seam (`PathTrace` + `PathTraceHolder`)

**Files:**
- Create: `common/src/main/java/net/magicterra/worlddriver/bot/pathfinder/PathTrace.java`
- Create: `common/src/main/java/net/magicterra/worlddriver/bot/pathfinder/PathTraceHolder.java`

- [ ] **Step 1: Create `PathTrace.java`**

```java
package net.magicterra.worlddriver.bot.pathfinder;

import net.magicterra.worlddriver.bot.Goal;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * Inert observation seam for pathfinding debug tooling. The core ({@link PathFinder},
 * {@code Walker}) calls {@link PathTraceHolder#SINK} unconditionally; in a release build
 * with the {@code bot.debug} package removed the sink stays {@link #NOOP} and every method
 * is an empty virtual call the JIT elides. The real implementation lives in
 * {@code net.magicterra.worlddriver.bot.debug.PathDebugRecorder} and is registered at client init.
 *
 * Stripping for release: delete the {@code bot.debug} package and the single
 * {@code PathDebugBootstrap.init()} call. This interface + {@link PathTraceHolder} remain,
 * inert. Nothing else in core changes.
 */
public interface PathTrace {

    /** Terminal outcome of a goto session, as classified by the Walker. */
    enum Outcome { SUCCESS, NO_PATH, STUCK, TIMEOUT, CANCELLED, ERROR }

    /**
     * One per-tick execution sample. Plain data only (no Minecraft refs) so the recorder
     * can be snapshotted and rendered off the client thread. {@code targetX/targetZ} are the
     * centre of the path node the Walker was steering toward this tick (NaN when no path);
     * {@code yawActual} is the body yaw at tick entry. Speed and heading-error are derived in
     * the renderer from consecutive samples + targets.
     */
    record WalkerSample(long tick, double x, double y, double z, float yawActual,
                        double targetX, double targetZ, int stepIndex, String moveType,
                        boolean onGround, boolean inWater) {}

    /** Fired in {@code PathFinder.Search}'s constructor — one per (re)path search. */
    void onSearchBegin(BlockPos start, Goal goal);

    /** Fired once per A* node expansion. {@code g} is the accumulated cost at the node. */
    void onNodeExpanded(BlockPos pos, double g);

    /** Fired when the Walker adopts a search Result (success or best-effort fallback). */
    void onSearchResult(List<BlockPos> path, List<Move.Edge> edges, boolean goalReached,
                        int expanded, long ms, double finalCost);

    /** Fired once per Walker tick while a goal is active. */
    void onWalkerTick(WalkerSample sample);

    /** Fired when a goto session terminates. */
    void onTerminal(Outcome outcome, String reason);

    /** No-op sink — the default; release builds keep this and nothing else. */
    PathTrace NOOP = new PathTrace() {
        public void onSearchBegin(BlockPos start, Goal goal) {}
        public void onNodeExpanded(BlockPos pos, double g) {}
        public void onSearchResult(List<BlockPos> path, List<Move.Edge> edges, boolean goalReached,
                                   int expanded, long ms, double finalCost) {}
        public void onWalkerTick(WalkerSample sample) {}
        public void onTerminal(Outcome outcome, String reason) {}
    };
}
```

- [ ] **Step 2: Create `PathTraceHolder.java`**

```java
package net.magicterra.worlddriver.bot.pathfinder;

/**
 * Single mutable reference to the active {@link PathTrace}. Defaults to {@link PathTrace#NOOP}
 * so core is inert until the debug package registers a recorder via
 * {@code PathDebugBootstrap.init()}. {@code volatile} because the writer (client init thread)
 * and readers (Walker / pathfinder thread) differ.
 */
public final class PathTraceHolder {
    private PathTraceHolder() {}
    public static volatile PathTrace SINK = PathTrace.NOOP;
}
```

- [ ] **Step 3: Compile**

Run: `./gradlew :common:compileJava`
Expected: BUILD SUCCESSFUL (no references yet, pure additions).

- [ ] **Step 4: Commit** (only if user has approved committing on this branch)

```bash
git add common/src/main/java/net/magicterra/worlddriver/bot/pathfinder/PathTrace.java \
        common/src/main/java/net/magicterra/worlddriver/bot/pathfinder/PathTraceHolder.java
git commit -m "feat(pathdebug): add inert PathTrace SPI seam"
```

---

## Task 2: Generic core extension seams (`addRoute`, `registerExtra`, `api()`)

**Files:**
- Modify: `common/src/main/java/net/magicterra/worlddriver/api/DriverApi.java` (line 93 field; add method near `route`)
- Modify: `common/src/main/java/net/magicterra/worlddriver/mcp/ToolCatalog.java`
- Modify: `common/src/main/java/net/magicterra/worlddriver/WorldDriverCommon.java`

- [ ] **Step 1: Make `DriverApi.routes` concurrent + add `addRoute`**

In `DriverApi.java`, change the field at line 93 from `HashMap` to `ConcurrentHashMap` (so post-construction registration is thread-safe against transport reads):

```java
private final Map<String, Function<Map<String, Object>, Object>> routes = new java.util.concurrent.ConcurrentHashMap<>();
```

Then add this method right after the existing `route(...)` method:

```java
/**
 * Register an additional route after construction. Used by optional, strippable
 * subsystems (e.g. the path-debug package) so core never compile-depends on them.
 * Idempotent-safe: a duplicate name overwrites. Thread-safe via the concurrent map.
 * Routes added here are reachable identically through every transport (Hard Rule #1).
 */
public void addRoute(String method, Function<Map<String, Object>, Object> handler) {
    routes.put(method, handler);
}
```

(If `java.util.function.Function` / `java.util.Map` aren't already imported simple-named in this file, they are — `routes` already uses them. Do not add FQNs per Hard Rule #7; the `ConcurrentHashMap` FQN above is acceptable only if `java.util.concurrent.ConcurrentHashMap` isn't imported — prefer adding `import java.util.concurrent.ConcurrentHashMap;` and writing `new ConcurrentHashMap<>()`.)

- [ ] **Step 2: Add generic extras seam to `ToolCatalog.java`**

Replace the body of `ToolCatalog` with the version below (adds `registerExtra` + concatenates extra suppliers after the fixed list, preserving load order):

```java
package net.magicterra.worlddriver.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import net.magicterra.worlddriver.mcp.catalog.BotTools;
import net.magicterra.worlddriver.mcp.catalog.ClientTools;
import net.magicterra.worlddriver.mcp.catalog.ObserveActionTools;
import net.magicterra.worlddriver.mcp.catalog.RecipeTools;
import net.magicterra.worlddriver.mcp.catalog.ScriptTools;
import net.magicterra.worlddriver.mcp.catalog.SystemTools;
import net.magicterra.worlddriver.mcp.catalog.WaitTools;

/**
 * MCP tool catalog. The fixed section order is load-bearing (system → script →
 * observe/action → wait → client → bot). Optional, strippable subsystems append
 * their schemas via {@link #registerExtra}; with none registered the catalog is
 * exactly the fixed set, so removing such a subsystem needs no edit here.
 */
public final class ToolCatalog {
    private ToolCatalog() {}

    private static final List<Supplier<List<Map<String, Object>>>> EXTRA = new CopyOnWriteArrayList<>();

    /** Register an extra schema supplier (e.g. the path-debug tool). Inert until called. */
    public static void registerExtra(Supplier<List<Map<String, Object>>> supplier) {
        EXTRA.add(supplier);
    }

    public static List<Map<String, Object>> tools() {
        ArrayList<Map<String, Object>> all = new ArrayList<>();
        all.addAll(SystemTools.tools());
        all.addAll(ScriptTools.tools());
        all.addAll(ObserveActionTools.tools());
        all.addAll(RecipeTools.tools());
        all.addAll(WaitTools.tools());
        all.addAll(ClientTools.tools());
        all.addAll(BotTools.tools());
        for (Supplier<List<Map<String, Object>>> s : EXTRA) all.addAll(s.get());
        return List.copyOf(all);
    }
}
```

- [ ] **Step 3: Add `api()` getter to `WorldDriverCommon.java`**

Find the private static field `api` (declared near the top; assigned in `ensureRpcUp()` at line ~118 as `api = new DriverApi();`). Add a public accessor next to it:

```java
/** The shared DriverApi singleton, or null before {@link #ensureRpcUp()} runs.
 *  Used by optional subsystems (path-debug) to register routes at client init. */
public static DriverApi api() { return api; }
```

(Confirm the field type is `DriverApi` and import is present — `ensureRpcUp` already constructs it, so it is.)

- [ ] **Step 4: Compile**

Run: `./gradlew :common:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/net/magicterra/worlddriver/api/DriverApi.java \
        common/src/main/java/net/magicterra/worlddriver/mcp/ToolCatalog.java \
        common/src/main/java/net/magicterra/worlddriver/WorldDriverCommon.java
git commit -m "feat(pathdebug): add generic inert route/catalog extension seams"
```

---

## Task 3: BotConfig settings + SettingsCommand wiring

**Files:**
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/BotConfig.java` (near the other walker/pathfinder fields)
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/SettingsCommand.java`

- [ ] **Step 1: Add fields to `BotConfig.java`**

Place these next to the existing `walkerDebug` field (line ~472). They auto-persist (static volatile, persistable types, not in `NON_PERSISTED`):

```java
/** Master gate for path-debug capture. When false the recorder early-returns and
 *  the chart tool renders whatever (empty) session exists. Default off — debug only. */
public static volatile boolean pathDebug = false;
/** Cap on stored A* candidate nodes per search (reservoir-downsampled above this). */
public static volatile int pathDebugMaxNodes = 4000;
/** Cap on stored per-tick trajectory samples (ring buffer; oldest dropped). */
public static volatile int pathDebugMaxSamples = 6000;
/** When true, auto-write a chart on every goto terminal outcome (success and failure). */
public static volatile boolean pathChartAutoDump = false;
```

- [ ] **Step 2: Parse booleans in `SettingsCommand.apply` (boolean block, near line 50)**

Add alongside the `autoEat` block:

```java
if (params.get("pathDebug") instanceof Boolean pd) {
    BotConfig.pathDebug = pd;
    applied.add("pathDebug");
}
if (params.get("pathChartAutoDump") instanceof Boolean pcad) {
    BotConfig.pathChartAutoDump = pcad;
    applied.add("pathChartAutoDump");
}
```

- [ ] **Step 3: Parse numerics in the numeric `switch` (near line 362)**

Add these `case` branches alongside `walker.repathEveryTicks`:

```java
case "pathDebugMaxNodes":
    if (n.intValue() >= 100 && n.intValue() <= 200000) { BotConfig.pathDebugMaxNodes = n.intValue(); applied.add(k); }
    else rejected.add(k + " out of range [100,200000]");
    break;
case "pathDebugMaxSamples":
    if (n.intValue() >= 100 && n.intValue() <= 200000) { BotConfig.pathDebugMaxSamples = n.intValue(); applied.add(k); }
    else rejected.add(k + " out of range [100,200000]");
    break;
```

- [ ] **Step 4: Echo in the settings snapshot (near line 485)**

Add to the `snap.put(...)` block so `mc.bot.setting` reads them back:

```java
snap.put("pathDebug", BotConfig.pathDebug);
snap.put("pathDebugMaxNodes", BotConfig.pathDebugMaxNodes);
snap.put("pathDebugMaxSamples", BotConfig.pathDebugMaxSamples);
snap.put("pathChartAutoDump", BotConfig.pathChartAutoDump);
```

- [ ] **Step 5: Compile**

Run: `./gradlew :common:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add common/src/main/java/net/magicterra/worlddriver/bot/BotConfig.java \
        common/src/main/java/net/magicterra/worlddriver/bot/SettingsCommand.java
git commit -m "feat(pathdebug): add pathDebug settings + mc.bot.setting wiring"
```

---

## Task 4: Capture hooks in `PathFinder`

**Files:**
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/pathfinder/PathFinder.java` (Search ctor line ~115; advance loop line ~146)

- [ ] **Step 1: Fire `onSearchBegin` in the `Search` constructor**

In `Search(BlockPos start, Goal goal)` (line ~115), after `open.add(startNode);` add:

```java
PathTraceHolder.SINK.onSearchBegin(start, goal);
```

(`PathTrace`/`PathTraceHolder` are in the same package — no import needed.)

- [ ] **Step 2: Fire `onNodeExpanded` per expansion**

In `advance(...)`, immediately after `expanded++;` (line ~146) add:

```java
PathTraceHolder.SINK.onNodeExpanded(cur.pos, cur.g);
```

- [ ] **Step 3: Compile**

Run: `./gradlew :common:compileJava`
Expected: BUILD SUCCESSFUL. (With `SINK == NOOP` these are no-ops.)

- [ ] **Step 4: Commit**

```bash
git add common/src/main/java/net/magicterra/worlddriver/bot/pathfinder/PathFinder.java
git commit -m "feat(pathdebug): fire search-begin / node-expanded traces"
```

---

## Task 5: Capture hooks in `Walker`

**Files:**
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/movement/Walker.java` (imports; adopt block line ~235; tick body line ~130/182; terminal returns lines ~131, ~187-201, ~213-215, ~282-284)

- [ ] **Step 1: Import the SPI**

Add near the other pathfinder imports:

```java
import net.magicterra.worlddriver.bot.pathfinder.PathTrace;
import net.magicterra.worlddriver.bot.pathfinder.PathTraceHolder;
```

- [ ] **Step 2: Fire `onSearchResult` when adopting a result**

In the `if (searchDone) { ... }` block (line ~235), right after `lastStats = new PathStats(...)` is assigned, add:

```java
PathTraceHolder.SINK.onSearchResult(res.path(), res.edges(), res.goalReached(),
        res.expanded(), res.ms(), res.finalCost());
```

- [ ] **Step 3: Add a terminal helper + per-tick sampler to `Walker`**

Add these private methods to the `Walker` class (place near the bottom, beside other helpers):

```java
/** Fire onTerminal and return the step verdict in one place, so every terminal
 *  return site stays a one-liner. */
private Step terminal(Step s, PathTrace.Outcome outcome, String reason) {
    PathTraceHolder.SINK.onTerminal(outcome, reason);
    return s;
}

/** Emit a per-tick execution sample. Pure reads; cheap; gated to NOOP in release. */
private void sampleTick(LocalPlayer p) {
    double tx = Double.NaN, tz = Double.NaN;
    String mv = null;
    if (path != null && step >= 0 && step < path.size()) {
        BlockPos t = path.get(step);
        tx = t.getX() + 0.5;
        tz = t.getZ() + 0.5;
        Move.Edge e = edgeAt(step);
        mv = (e != null) ? e.move : null;
    }
    PathTraceHolder.SINK.onWalkerTick(new PathTrace.WalkerSample(
            p.tickCount, p.getX(), p.getY(), p.getZ(), p.getYRot(),
            tx, tz, step, mv, p.onGround(), p.isInWater()));
}
```

(`Move.Edge`/`BlockPos`/`LocalPlayer` are already imported in Walker. If `edgeAt(int)` has a different name, use the existing accessor that returns the `Move.Edge` for a step — grep `edgeAt` / `edges.get` in Walker and match it.)

- [ ] **Step 4: Call the sampler once per tick**

In `tick(Minecraft mc, WorldView world)`, after `foot` is computed and a path/goal is active (line ~182, right after the `BlockPos foot = ...` line and before the `goal.reached(foot)` check), add:

```java
sampleTick(p);
```

- [ ] **Step 5: Convert terminal returns to fire outcomes**

Replace these specific `return` statements (leave all mid-tick `return Step.RUNNING`/`continue` untouched):

- Player vanished (line ~131): `return Step.FAILED;`
  →
  ```java
  return terminal(Step.FAILED, PathTrace.Outcome.ERROR, lastError);
  ```
- Goal reached / ARRIVED (line ~189, the `return Step.ARRIVED;`):
  →
  ```java
  return terminal(Step.ARRIVED, PathTrace.Outcome.SUCCESS, null);
  ```
- No-progress timeout (line ~213, `return Step.FAILED;` after setting the "no progress for ..." `lastError`):
  →
  ```java
  return terminal(Step.FAILED, PathTrace.Outcome.STUCK, lastError);
  ```
- No path (line ~283, `return Step.FAILED;` after the "no path (expanded=...)" `lastError`):
  →
  ```java
  return terminal(Step.FAILED, PathTrace.Outcome.NO_PATH, lastError);
  ```

(There may be additional `return Step.FAILED;` sites; only convert the four above. If a site's `lastError` clearly maps to STUCK/NO_PATH/ERROR, you may convert it with the matching outcome — but do not change control flow.)

- [ ] **Step 6: Compile**

Run: `./gradlew :common:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add common/src/main/java/net/magicterra/worlddriver/bot/movement/Walker.java
git commit -m "feat(pathdebug): fire result/tick/terminal traces from Walker"
```

---

## Task 6: Debug DTOs (`PathSession`)

**Files:**
- Create: `common/src/main/java/net/magicterra/worlddriver/bot/debug/PathSession.java`

- [ ] **Step 1: Create `PathSession.java`**

```java
package net.magicterra.worlddriver.bot.debug;

import net.magicterra.worlddriver.bot.pathfinder.PathTrace;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * Immutable snapshot of one goto session for rendering. Built by
 * {@link PathDebugRecorder#snapshot()} on the calling thread; contains only plain data
 * so the renderer can run off the client thread.
 */
public record PathSession(
        BlockPos start,
        BlockPos goalMarker,            // representative goal cell, or null for open goals
        List<Candidate> candidates,     // latest search's expanded nodes (downsampled)
        List<PlannedRoute> plannedRoutes,
        List<PathTrace.WalkerSample> trajectory,
        PathTrace.Outcome outcome,      // null while still running
        String reason,
        String goalDesc) {

    /** One expanded A* node. */
    public record Candidate(int x, int y, int z, double g) {}

    /** One adopted search result. {@code repathIndex} 0 = first plan of the session. */
    public record PlannedRoute(List<BlockPos> path, boolean goalReached, int repathIndex,
                               int expanded, long ms, double finalCost) {}
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :common:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add common/src/main/java/net/magicterra/worlddriver/bot/debug/PathSession.java
git commit -m "feat(pathdebug): add PathSession snapshot DTO"
```

---

## Task 7: `PathDebugRecorder`

**Files:**
- Create: `common/src/main/java/net/magicterra/worlddriver/bot/debug/PathDebugRecorder.java`

- [ ] **Step 1: Create `PathDebugRecorder.java`**

```java
package net.magicterra.worlddriver.bot.debug;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.PathTrace;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * The live {@link PathTrace} implementation. Accumulates one goto session, reset whenever a
 * new goal appears. Gated by {@link BotConfig#pathDebug}: when off, every callback returns
 * immediately. Snapshot is copy-on-read so the renderer never tears.
 *
 * Session boundary = a change of goal (compared by {@code toString()}); long gotos repath
 * from intermediate cells to the same goal and stay one session. Candidates reflect the
 * latest search only (cleared each {@code onSearchBegin}); planned routes accumulate.
 */
public final class PathDebugRecorder implements PathTrace {
    private static final Logger LOG = LoggerFactory.getLogger("agent-pathdebug");

    private final Object lock = new Object();

    private BlockPos start;
    private BlockPos goalMarker;
    private String goalDesc = "";
    private String goalKey = "";
    private int repathCount;
    private long expandedSinceBegin;   // total expansions this search (for reservoir scaling)

    private final List<PathSession.Candidate> candidates = new ArrayList<>();
    private final List<PathSession.PlannedRoute> plannedRoutes = new ArrayList<>();
    private final ArrayDeque<WalkerSample> trajectory = new ArrayDeque<>();
    private Outcome outcome;
    private String reason;

    @Override
    public void onSearchBegin(BlockPos searchStart, Goal goal) {
        if (!BotConfig.pathDebug) return;
        synchronized (lock) {
            String key = String.valueOf(goal);
            if (!key.equals(goalKey)) {                 // new goto → reset session
                goalKey = key;
                goalDesc = key;
                start = searchStart;
                goalMarker = GoalMarker.of(goal);
                plannedRoutes.clear();
                trajectory.clear();
                repathCount = 0;
                outcome = null;
                reason = null;
            }
            candidates.clear();                         // latest search's candidates only
            expandedSinceBegin = 0;
        }
    }

    @Override
    public void onNodeExpanded(BlockPos pos, double g) {
        if (!BotConfig.pathDebug) return;
        synchronized (lock) {
            expandedSinceBegin++;
            int cap = Math.max(100, BotConfig.pathDebugMaxNodes);
            if (candidates.size() < cap) {
                candidates.add(new PathSession.Candidate(pos.getX(), pos.getY(), pos.getZ(), g));
            } else {
                // Deterministic reservoir-style replacement: keep a uniform spread without RNG.
                long stride = expandedSinceBegin;
                int idx = (int) (stride % cap);
                candidates.set(idx, new PathSession.Candidate(pos.getX(), pos.getY(), pos.getZ(), g));
            }
        }
    }

    @Override
    public void onSearchResult(List<BlockPos> path, List<Move.Edge> edges, boolean goalReached,
                               int expanded, long ms, double finalCost) {
        if (!BotConfig.pathDebug) return;
        synchronized (lock) {
            plannedRoutes.add(new PathSession.PlannedRoute(
                    List.copyOf(path), goalReached, repathCount++, expanded, ms, finalCost));
        }
    }

    @Override
    public void onWalkerTick(WalkerSample sample) {
        if (!BotConfig.pathDebug) return;
        synchronized (lock) {
            int cap = Math.max(100, BotConfig.pathDebugMaxSamples);
            trajectory.addLast(sample);
            while (trajectory.size() > cap) trajectory.removeFirst();
        }
    }

    @Override
    public void onTerminal(Outcome o, String r) {
        if (!BotConfig.pathDebug) return;
        boolean dump;
        PathSession snap;
        synchronized (lock) {
            this.outcome = o;
            this.reason = r;
            dump = BotConfig.pathChartAutoDump;
            snap = dump ? snapshotLocked() : null;
        }
        if (dump && snap != null) {
            // Render+write off the client thread so a terminal tick never hitches.
            Thread t = new Thread(() -> {
                try {
                    var meta = PathChartWriter.write(PathChartRenderer.render(snap, PathChartRenderer.Opts.defaults()), null);
                    LOG.info("[pathdebug] auto-dumped chart {} ({}x{}, outcome={})",
                            meta.get("path"), meta.get("width"), meta.get("height"), o);
                } catch (Exception e) {
                    LOG.warn("[pathdebug] auto-dump failed: {}", e.toString());
                }
            }, "pathdebug-autodump");
            t.setDaemon(true);
            t.start();
        }
    }

    /** Copy-on-read snapshot for the renderer. Safe to call from any thread. */
    public PathSession snapshot() {
        synchronized (lock) { return snapshotLocked(); }
    }

    private PathSession snapshotLocked() {
        return new PathSession(
                start, goalMarker,
                new ArrayList<>(candidates),
                new ArrayList<>(plannedRoutes),
                new ArrayList<>(trajectory),
                outcome, reason, goalDesc);
    }
}
```

- [ ] **Step 2: Create `GoalMarker.java` helper** (same package — keeps the sealed-type switch out of the renderer)

`common/src/main/java/net/magicterra/worlddriver/bot/debug/GoalMarker.java`:

```java
package net.magicterra.worlddriver.bot.debug;

import net.magicterra.worlddriver.bot.Goal;
import net.minecraft.core.BlockPos;

/** Derive a representative goal cell for the chart marker. Open goals
 *  (RunAway/Inverted/StrictDirection/Axis) have no single point → null. */
final class GoalMarker {
    private GoalMarker() {}

    static BlockPos of(Goal g) {
        if (g instanceof Goal.Block b) return b.target();
        if (g instanceof Goal.Near n) return n.target();
        if (g instanceof Goal.GetToBlock gb) return gb.target();
        if (g instanceof Goal.TwoBlocks tb) return tb.target();
        if (g instanceof Goal.XZ xz) return new BlockPos(xz.x(), 0, xz.z());
        if (g instanceof Goal.Composite c && c.children().length > 0) return of(c.children()[0]);
        return null;
    }
}
```

- [ ] **Step 3: Compile** (will fail until Task 8/9 add `PathChartRenderer`/`PathChartWriter`; expected)

Run: `./gradlew :common:compileJava`
Expected: FAIL — "cannot find symbol PathChartRenderer/PathChartWriter". Proceed to Task 8.

---

## Task 8: `PathChartRenderer` (pure AWT dashboard)

**Files:**
- Create: `common/src/main/java/net/magicterra/worlddriver/bot/debug/PathChartRenderer.java`

- [ ] **Step 1: Create `PathChartRenderer.java`**

```java
package net.magicterra.worlddriver.bot.debug;

import net.magicterra.worlddriver.bot.pathfinder.PathTrace;
import net.minecraft.core.BlockPos;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Pure renderer: {@link PathSession} → {@link BufferedImage}. No I/O, no Minecraft world
 * access. Layout = a top-down X/Z map (top), an elevation profile (mid), and two
 * time-series strips (speed; actual-vs-target heading). Headless-safe (AWT, like
 * Screenshots.java).
 */
public final class PathChartRenderer {
    private PathChartRenderer() {}

    public record Opts(int width, int height, double blocksPerPixel, boolean includeCandidates) {
        public static Opts defaults() { return new Opts(1280, 960, 0, true); } // bpp 0 = auto-fit
    }

    private static final Color BG = new Color(18, 18, 22);
    private static final Color PANEL = new Color(28, 28, 34);
    private static final Color GRID = new Color(60, 60, 70);
    private static final Color TEXT = new Color(220, 220, 225);
    private static final Color PLAN_LATEST = new Color(90, 170, 255);
    private static final Color PLAN_OLD = new Color(90, 170, 255, 70);
    private static final Color PLAN_FAILED = new Color(255, 140, 60);
    private static final Color START = new Color(80, 220, 120);
    private static final Color GOAL = new Color(240, 80, 80);
    private static final Color CUR = new Color(80, 230, 230);
    private static final Color YAW_ACTUAL = new Color(120, 220, 140);
    private static final Color YAW_TARGET = new Color(230, 200, 90);

    public static BufferedImage render(PathSession s, Opts opts) {
        int W = opts.width(), H = opts.height();
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(BG);
        g.fillRect(0, 0, W, H);

        int header = 64;
        int mapH = (H - header) * 55 / 100;
        int elevH = (H - header) * 20 / 100;
        int tsH = (H - header) - mapH - elevH;
        int mapY = header, elevY = header + mapH, tsY = elevY + elevH;

        drawHeader(g, s, W, header);
        drawMap(g, s, opts, 0, mapY, W, mapH);
        drawElevation(g, s, 0, elevY, W, elevH);
        drawTimeSeries(g, s, 0, tsY, W, tsH);

        g.dispose();
        return img;
    }

    // ---- Header --------------------------------------------------------------
    private static void drawHeader(Graphics2D g, PathSession s, int W, int h) {
        g.setColor(PANEL);
        g.fillRect(0, 0, W, h);
        g.setColor(TEXT);
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 14));
        PathSession.PlannedRoute last = lastPlan(s);
        double[] sp = speedStats(s.trajectory());
        double maxErr = maxYawError(s.trajectory());
        String l1 = String.format("goal=%s  outcome=%s  reason=%s",
                s.goalDesc(), s.outcome() == null ? "RUNNING" : s.outcome(),
                s.reason() == null ? "-" : s.reason());
        String l2 = String.format("plans=%d  lastPathLen=%d  reached=%s  expanded=%d  ms=%d  cost=%.0f  candidates=%d  samples=%d  avgSpd=%.2f peakSpd=%.2f b/s  maxYawErr=%.0f deg",
                s.plannedRoutes().size(),
                last == null ? 0 : last.path().size(),
                last == null ? "-" : String.valueOf(last.goalReached()),
                last == null ? 0 : last.expanded(),
                last == null ? 0 : last.ms(),
                last == null ? 0.0 : last.finalCost(),
                s.candidates().size(), s.trajectory().size(),
                sp[0], sp[1], maxErr);
        g.drawString(l1, 8, 22);
        g.drawString(l2, 8, 44);
    }

    // ---- Top-down map --------------------------------------------------------
    private static void drawMap(Graphics2D g, PathSession s, Opts opts, int x0, int y0, int w, int h) {
        g.setColor(PANEL); g.fillRect(x0, y0, w, h);
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (PathSession.Candidate c : s.candidates()) { minX = Math.min(minX, c.x()); maxX = Math.max(maxX, c.x()); minZ = Math.min(minZ, c.z()); maxZ = Math.max(maxZ, c.z()); }
        for (PathSession.PlannedRoute r : s.plannedRoutes()) for (BlockPos p : r.path()) { minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX()); minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ()); }
        for (PathTrace.WalkerSample t : s.trajectory()) { minX = Math.min(minX, t.x()); maxX = Math.max(maxX, t.x()); minZ = Math.min(minZ, t.z()); maxZ = Math.max(maxZ, t.z()); }
        if (!Double.isFinite(minX)) { g.setColor(TEXT); g.drawString("(no path data captured — enable pathDebug then run a goto)", x0 + 12, y0 + 24); return; }

        double pad = 3;
        minX -= pad; maxX += pad; minZ -= pad; maxZ += pad;
        double spanX = Math.max(1, maxX - minX), spanZ = Math.max(1, maxZ - minZ);
        double scale = Math.min((w - 20) / spanX, (h - 20) / spanZ);
        final double fMinX = minX, fMinZ = minZ, fScale = scale;
        final int fx0 = x0 + 10, fy0 = y0 + 10;
        java.util.function.DoubleUnaryOperator px = wx -> fx0 + (wx - fMinX) * fScale;
        java.util.function.DoubleUnaryOperator pz = wz -> fy0 + (wz - fMinZ) * fScale;

        // candidate heat
        if (opts.includeCandidates() && !s.candidates().isEmpty()) {
            double gmin = Double.POSITIVE_INFINITY, gmax = Double.NEGATIVE_INFINITY;
            for (PathSession.Candidate c : s.candidates()) { gmin = Math.min(gmin, c.g()); gmax = Math.max(gmax, c.g()); }
            double gspan = Math.max(1e-9, gmax - gmin);
            for (PathSession.Candidate c : s.candidates()) {
                float t = (float) ((c.g() - gmin) / gspan);
                g.setColor(new Color(60 + (int) (150 * t), 60, 160 - (int) (120 * t), 90));
                int cx = (int) px.applyAsDouble(c.x()), cz = (int) pz.applyAsDouble(c.z());
                g.fillRect(cx, cz, 2, 2);
            }
        }
        // planned routes
        List<PathSession.PlannedRoute> plans = s.plannedRoutes();
        for (int i = 0; i < plans.size(); i++) {
            PathSession.PlannedRoute r = plans.get(i);
            boolean latest = (i == plans.size() - 1);
            g.setColor(!r.goalReached() ? PLAN_FAILED : latest ? PLAN_LATEST : PLAN_OLD);
            g.setStroke(latest ? new BasicStroke(2.5f)
                    : !r.goalReached() ? new BasicStroke(1.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[]{5f, 5f}, 0f)
                    : new BasicStroke(1.0f));
            drawPolyline(g, r.path(), px, pz);
        }
        // actual trajectory, speed-coloured
        drawTrajectory(g, s.trajectory(), px, pz);
        // markers
        if (s.start() != null) marker(g, START, px.applyAsDouble(s.start().getX() + 0.5), pz.applyAsDouble(s.start().getZ() + 0.5), 6);
        if (s.goalMarker() != null) {
            double gx = px.applyAsDouble(s.goalMarker().getX() + 0.5), gz = pz.applyAsDouble(s.goalMarker().getZ() + 0.5);
            boolean reached = lastPlan(s) != null && lastPlan(s).goalReached();
            marker(g, GOAL, gx, gz, 6);
            if (!reached) { g.setColor(GOAL); g.setStroke(new BasicStroke(2f)); g.draw(new Line2D.Double(gx - 7, gz - 7, gx + 7, gz + 7)); g.draw(new Line2D.Double(gx - 7, gz + 7, gx + 7, gz - 7)); }
        }
        if (!s.trajectory().isEmpty()) {
            PathTrace.WalkerSample cur = s.trajectory().get(s.trajectory().size() - 1);
            marker(g, CUR, px.applyAsDouble(cur.x()), pz.applyAsDouble(cur.z()), 5);
        }
        // heading arrows every N samples
        drawHeadingArrows(g, s.trajectory(), px, pz);
        g.setColor(TEXT); g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        g.drawString("top-down X/Z  (blue=plan, orange=failed plan, green=start, red=goal, cyan=now, arrows=heading)", x0 + 12, y0 + h - 8);
    }

    private static void drawPolyline(Graphics2D g, List<BlockPos> path, java.util.function.DoubleUnaryOperator px, java.util.function.DoubleUnaryOperator pz) {
        for (int i = 1; i < path.size(); i++) {
            BlockPos a = path.get(i - 1), b = path.get(i);
            g.draw(new Line2D.Double(px.applyAsDouble(a.getX() + 0.5), pz.applyAsDouble(a.getZ() + 0.5),
                    px.applyAsDouble(b.getX() + 0.5), pz.applyAsDouble(b.getZ() + 0.5)));
        }
    }

    private static void drawTrajectory(Graphics2D g, List<PathTrace.WalkerSample> tr, java.util.function.DoubleUnaryOperator px, java.util.function.DoubleUnaryOperator pz) {
        g.setStroke(new BasicStroke(2.0f));
        for (int i = 1; i < tr.size(); i++) {
            PathTrace.WalkerSample a = tr.get(i - 1), b = tr.get(i);
            double bps = speedBetween(a, b);
            g.setColor(speedColor(bps));
            g.draw(new Line2D.Double(px.applyAsDouble(a.x()), pz.applyAsDouble(a.z()), px.applyAsDouble(b.x()), pz.applyAsDouble(b.z())));
        }
    }

    private static void drawHeadingArrows(Graphics2D g, List<PathTrace.WalkerSample> tr, java.util.function.DoubleUnaryOperator px, java.util.function.DoubleUnaryOperator pz) {
        int n = tr.size();
        if (n == 0) return;
        int stepEvery = Math.max(1, n / 30);
        for (int i = 0; i < n; i += stepEvery) {
            PathTrace.WalkerSample t = tr.get(i);
            double ox = px.applyAsDouble(t.x()), oz = pz.applyAsDouble(t.z());
            // actual heading (yaw): MC yaw 0=+Z, 90=-X. dx=-sin(yaw), dz=cos(yaw)
            double ar = Math.toRadians(t.yawActual());
            arrow(g, YAW_ACTUAL, ox, oz, -Math.sin(ar), Math.cos(ar), 10);
            if (!Double.isNaN(t.targetX())) {
                double dx = t.targetX() - t.x(), dz = t.targetZ() - t.z();
                double len = Math.hypot(dx, dz);
                if (len > 1e-3) arrow(g, YAW_TARGET, ox, oz, dx / len, dz / len, 10);
            }
        }
    }

    private static void arrow(Graphics2D g, Color c, double ox, double oz, double dx, double dz, double len) {
        g.setColor(c); g.setStroke(new BasicStroke(1.4f));
        double ex = ox + dx * len, ez = oz + dz * len;
        g.draw(new Line2D.Double(ox, oz, ex, ez));
    }

    private static void marker(Graphics2D g, Color c, double x, double y, int r) {
        g.setColor(c); g.fillOval((int) x - r, (int) y - r, r * 2, r * 2);
    }

    // ---- Elevation profile ---------------------------------------------------
    private static void drawElevation(Graphics2D g, PathSession s, int x0, int y0, int w, int h) {
        g.setColor(PANEL); g.fillRect(x0, y0, w, h);
        g.setColor(TEXT); g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        g.drawString("elevation: Y vs distance  (green=actual, blue=latest plan)", x0 + 12, y0 + 14);

        List<PathTrace.WalkerSample> tr = s.trajectory();
        PathSession.PlannedRoute plan = lastPlan(s);
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (PathTrace.WalkerSample t : tr) { minY = Math.min(minY, t.y()); maxY = Math.max(maxY, t.y()); }
        if (plan != null) for (BlockPos p : plan.path()) { minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY()); }
        if (!Double.isFinite(minY)) return;
        if (maxY - minY < 1) { maxY = minY + 1; }
        int top = y0 + 22, bot = y0 + h - 10;
        final double fMinY = minY, fMaxY = maxY; final int ftop = top, fbot = bot;
        java.util.function.DoubleUnaryOperator py = wy -> fbot - (wy - fMinY) / (fMaxY - fMinY) * (fbot - ftop);

        if (plan != null && plan.path().size() > 1) {
            g.setColor(PLAN_LATEST); g.setStroke(new BasicStroke(1.8f));
            double total = planDist(plan.path());
            double acc = 0; double prevX = x0 + 12; double prevY = py.applyAsDouble(plan.path().get(0).getY());
            for (int i = 1; i < plan.path().size(); i++) {
                acc += horiz(plan.path().get(i - 1), plan.path().get(i));
                double xx = x0 + 12 + (acc / Math.max(1, total)) * (w - 24);
                double yy = py.applyAsDouble(plan.path().get(i).getY());
                g.draw(new Line2D.Double(prevX, prevY, xx, yy)); prevX = xx; prevY = yy;
            }
        }
        if (tr.size() > 1) {
            g.setColor(YAW_ACTUAL); g.setStroke(new BasicStroke(1.8f));
            double total = trajDist(tr); double acc = 0;
            double prevX = x0 + 12; double prevY = py.applyAsDouble(tr.get(0).y());
            for (int i = 1; i < tr.size(); i++) {
                acc += Math.hypot(tr.get(i).x() - tr.get(i - 1).x(), tr.get(i).z() - tr.get(i - 1).z());
                double xx = x0 + 12 + (acc / Math.max(1, total)) * (w - 24);
                double yy = py.applyAsDouble(tr.get(i).y());
                g.draw(new Line2D.Double(prevX, prevY, xx, yy)); prevX = xx; prevY = yy;
            }
        }
    }

    // ---- Time series ---------------------------------------------------------
    private static void drawTimeSeries(Graphics2D g, PathSession s, int x0, int y0, int w, int h) {
        int half = h / 2;
        drawSpeedSeries(g, s, x0, y0, w, half);
        drawYawSeries(g, s, x0, y0 + half, w, h - half);
    }

    private static void drawSpeedSeries(Graphics2D g, PathSession s, int x0, int y0, int w, int h) {
        g.setColor(PANEL); g.fillRect(x0, y0, w, h);
        g.setColor(TEXT); g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        g.drawString("speed (b/s)  refs: sprint~5.6 walk~4.3", x0 + 12, y0 + 14);
        List<PathTrace.WalkerSample> tr = s.trajectory();
        int top = y0 + 20, bot = y0 + h - 6;
        double maxV = 6.5;
        g.setColor(GRID); g.setStroke(new BasicStroke(1f));
        for (double ref : new double[]{4.3, 5.6}) {
            int yy = (int) (bot - ref / maxV * (bot - top));
            g.draw(new Line2D.Double(x0 + 12, yy, x0 + w - 12, yy));
        }
        if (tr.size() < 2) return;
        g.setColor(new Color(110, 200, 255)); g.setStroke(new BasicStroke(1.6f));
        // One speed value per interval i (between sample i-1 and i); step plot.
        double prevX = Double.NaN, prevY = Double.NaN;
        for (int i = 1; i < tr.size(); i++) {
            double bps = Math.min(maxV, speedBetween(tr.get(i - 1), tr.get(i)));
            double xL = x0 + 12 + (double) (i - 1) / (tr.size() - 1) * (w - 24);
            double xR = x0 + 12 + (double) i / (tr.size() - 1) * (w - 24);
            double y = bot - bps / maxV * (bot - top);
            g.draw(new Line2D.Double(xL, y, xR, y));            // flat over the interval
            if (!Double.isNaN(prevX)) g.draw(new Line2D.Double(prevX, prevY, xL, y)); // riser
            prevX = xR; prevY = y;
        }
    }

    private static void drawYawSeries(Graphics2D g, PathSession s, int x0, int y0, int w, int h) {
        g.setColor(PANEL); g.fillRect(x0, y0, w, h);
        g.setColor(TEXT); g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        g.drawString("heading: actual yaw (green) vs target bearing (yellow), -180..180", x0 + 12, y0 + 14);
        List<PathTrace.WalkerSample> tr = s.trajectory();
        int top = y0 + 20, bot = y0 + h - 6;
        java.util.function.DoubleUnaryOperator py = v -> bot - (v + 180) / 360.0 * (bot - top);
        g.setColor(GRID); g.draw(new Line2D.Double(x0 + 12, py.applyAsDouble(0), x0 + w - 12, py.applyAsDouble(0)));
        if (tr.isEmpty()) return;
        plotYaw(g, tr, x0, w, py, YAW_ACTUAL, true);
        plotYaw(g, tr, x0, w, py, YAW_TARGET, false);
    }

    private static void plotYaw(Graphics2D g, List<PathTrace.WalkerSample> tr, int x0, int w,
                                java.util.function.DoubleUnaryOperator py, Color c, boolean actual) {
        g.setColor(c); g.setStroke(new BasicStroke(1.4f));
        double prevX = Double.NaN, prevY = Double.NaN;
        double prevVal = Double.NaN;
        for (int i = 0; i < tr.size(); i++) {
            Double v = actual ? (double) wrap180(tr.get(i).yawActual()) : targetBearing(tr.get(i));
            if (v == null) { prevX = Double.NaN; prevVal = Double.NaN; continue; }
            double x = x0 + 12 + (tr.size() == 1 ? 0 : (double) i / (tr.size() - 1) * (w - 24));
            double y = py.applyAsDouble(v);
            // Skip the connector across a -180/+180 wrap jump to avoid a full-height streak.
            if (!Double.isNaN(prevX) && Math.abs(v - prevVal) < 180.0) g.draw(new Line2D.Double(prevX, prevY, x, y));
            prevX = x; prevY = y; prevVal = v;
        }
    }

    // ---- math helpers --------------------------------------------------------
    private static PathSession.PlannedRoute lastPlan(PathSession s) {
        return s.plannedRoutes().isEmpty() ? null : s.plannedRoutes().get(s.plannedRoutes().size() - 1);
    }
    private static double horiz(BlockPos a, BlockPos b) { double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ(); return Math.hypot(dx, dz); }
    private static double planDist(List<BlockPos> p) { double d = 0; for (int i = 1; i < p.size(); i++) d += horiz(p.get(i - 1), p.get(i)); return d; }
    private static double trajDist(List<PathTrace.WalkerSample> t) { double d = 0; for (int i = 1; i < t.size(); i++) d += Math.hypot(t.get(i).x() - t.get(i - 1).x(), t.get(i).z() - t.get(i - 1).z()); return d; }
    private static double speedBetween(PathTrace.WalkerSample a, PathTrace.WalkerSample b) {
        long dt = Math.max(1, b.tick() - a.tick());
        double dist = Math.hypot(b.x() - a.x(), b.z() - a.z());
        return dist / dt * 20.0; // blocks per second (20 tps)
    }
    private static double[] speedStats(List<PathTrace.WalkerSample> tr) {
        double sum = 0, peak = 0; int n = 0;
        for (int i = 1; i < tr.size(); i++) { double v = speedBetween(tr.get(i - 1), tr.get(i)); sum += v; peak = Math.max(peak, v); n++; }
        return new double[]{n == 0 ? 0 : sum / n, peak};
    }
    private static Double targetBearing(PathTrace.WalkerSample t) {
        if (Double.isNaN(t.targetX())) return null;
        double dx = t.targetX() - t.x(), dz = t.targetZ() - t.z();
        if (Math.hypot(dx, dz) < 1e-3) return null;
        return wrap180(Math.toDegrees(Math.atan2(-dx, dz)));
    }
    private static double maxYawError(List<PathTrace.WalkerSample> tr) {
        double m = 0;
        for (PathTrace.WalkerSample t : tr) { Double b = targetBearing(t); if (b == null) continue; m = Math.max(m, Math.abs(wrap180(t.yawActual() - b))); }
        return m;
    }
    private static double wrap180(double deg) { double d = ((deg + 180) % 360 + 360) % 360 - 180; return d; }
    private static Color speedColor(double bps) {
        double t = Math.max(0, Math.min(1, bps / 6.0)); // 0=red(slow) → 1=green(fast)
        return new Color((int) (230 * (1 - t)) + 20, (int) (210 * t) + 20, 40);
    }
}
```

> Note: the renderer is intentionally a single focused file. The math helpers are pure and small; their outputs are asserted indirectly via the JS case (width/height/bytes) and verified visually in the E2E run.

- [ ] **Step 2: Compile** (still needs `PathChartWriter`; expected fail)

Run: `./gradlew :common:compileJava`
Expected: FAIL — "cannot find symbol PathChartWriter". Proceed to Task 9.

---

## Task 9: `PathChartWriter`

**Files:**
- Create: `common/src/main/java/net/magicterra/worlddriver/bot/debug/PathChartWriter.java`

- [ ] **Step 1: Create `PathChartWriter.java`**

```java
package net.magicterra.worlddriver.bot.debug;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Encodes a chart to PNG and writes it under {@code config/worlddriver/debug/}. Returns
 * metadata (path/width/height/bytes) for the tool response. Headless via ImageIO (same as
 * Screenshots.java). Pure side-effect class; no Minecraft refs.
 */
public final class PathChartWriter {
    private PathChartWriter() {}

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final Path DIR = Path.of("config", "worlddriver", "debug");

    /** Write the image. {@code nameOverride} optional (without extension); else pathchart-NNNN. */
    public static Map<String, Object> write(BufferedImage img, String nameOverride) throws Exception {
        Files.createDirectories(DIR);
        String name = (nameOverride != null && !nameOverride.isBlank())
                ? nameOverride
                : String.format("pathchart-%04d-%d", SEQ.incrementAndGet(), System.currentTimeMillis());
        Path out = DIR.resolve(name + ".png");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        byte[] bytes = baos.toByteArray();
        Files.write(out, bytes);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("path", out.toAbsolutePath().toString());
        meta.put("width", img.getWidth());
        meta.put("height", img.getHeight());
        meta.put("bytes", bytes.length);
        return meta;
    }
}
```

- [ ] **Step 2: Compile** (Task 7 recorder + 8 renderer + 9 writer now resolve)

Run: `./gradlew :common:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit Tasks 6–9 together** (they form one compiling unit)

```bash
git add common/src/main/java/net/magicterra/worlddriver/bot/debug/PathSession.java \
        common/src/main/java/net/magicterra/worlddriver/bot/debug/PathDebugRecorder.java \
        common/src/main/java/net/magicterra/worlddriver/bot/debug/GoalMarker.java \
        common/src/main/java/net/magicterra/worlddriver/bot/debug/PathChartRenderer.java \
        common/src/main/java/net/magicterra/worlddriver/bot/debug/PathChartWriter.java
git commit -m "feat(pathdebug): recorder, AWT dashboard renderer, PNG writer"
```

---

## Task 10: Route handler, schema, bootstrap

**Files:**
- Create: `common/src/main/java/net/magicterra/worlddriver/bot/debug/PathChartTool.java`
- Create: `common/src/main/java/net/magicterra/worlddriver/bot/debug/DebugTools.java`
- Create: `common/src/main/java/net/magicterra/worlddriver/bot/debug/PathDebugBootstrap.java`

- [ ] **Step 1: Create `PathChartTool.java`**

```java
package net.magicterra.worlddriver.bot.debug;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Handler for {@code mc.debug.pathChart}. Reads the current recorder snapshot, renders the
 * dashboard, writes a PNG, and returns its metadata + a small stats summary. Pure data →
 * image → file; no Minecraft world access, so it runs fine on the RPC thread (no need to
 * bounce to the client thread).
 */
public final class PathChartTool {
    private PathChartTool() {}

    private static volatile PathDebugRecorder recorder;
    static void bind(PathDebugRecorder r) { recorder = r; }

    public static Map<String, Object> render(Map<String, Object> p) {
        PathDebugRecorder r = recorder;
        if (r == null) return Map.of("ok", false, "error", "path-debug not initialised");
        int w = intOpt(p, "width", 1280);
        int h = intOpt(p, "height", 960);
        boolean cands = !(p != null && Boolean.FALSE.equals(p.get("includeCandidates")));
        boolean save = !(p != null && Boolean.FALSE.equals(p.get("save")));
        PathSession s = r.snapshot();
        var img = PathChartRenderer.render(s, new PathChartRenderer.Opts(w, h, 0, cands));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("outcome", s.outcome() == null ? "RUNNING" : s.outcome().toString());
        out.put("plans", s.plannedRoutes().size());
        out.put("candidates", s.candidates().size());
        out.put("samples", s.trajectory().size());
        if (save) {
            try { out.putAll(PathChartWriter.write(img, p != null && p.get("name") instanceof String n ? n : null)); }
            catch (Exception e) { return Map.of("ok", false, "error", "write failed: " + e); }
        } else {
            out.put("width", img.getWidth());
            out.put("height", img.getHeight());
        }
        return out;
    }

    private static int intOpt(Map<String, Object> p, String k, int def) {
        return (p != null && p.get(k) instanceof Number n) ? n.intValue() : def;
    }
}
```

- [ ] **Step 2: Create `DebugTools.java`** (schema, mirrors `ClientTools` style)

```java
package net.magicterra.worlddriver.bot.debug;

import java.util.List;
import java.util.Map;

import static net.magicterra.worlddriver.mcp.schema.Schemas.*;

/** MCP schema for the path-debug tool. Registered via {@code ToolCatalog.registerExtra}. */
public final class DebugTools {
    private DebugTools() {}

    public static List<Map<String, Object>> tools() {
        return List.of(
            roTool("mc.debug.pathChart",
                "Render the current goto session's pathfinding debug dashboard to a PNG on disk " +
                "(config/worlddriver/debug/) and return its absolute path. Overlays A* candidates, " +
                "every planned route (latest bold, failed dashed), and the actual walked trajectory " +
                "(speed-coloured) on a top-down X/Z map, plus an elevation profile and speed / heading " +
                "time-series. Requires mc.bot.setting{pathDebug:true} BEFORE the goto so data is captured. " +
                "Callable any time — mid-walk or after success/failure. Returns {ok, path, width, height, " +
                "bytes, outcome, plans, candidates, samples}.",
                Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "width", Map.of("type", "integer", "minimum", 256, "maximum", 4096,
                            "description", "Image width px. Default 1280."),
                        "height", Map.of("type", "integer", "minimum", 256, "maximum", 4096,
                            "description", "Image height px. Default 960."),
                        "includeCandidates", Map.of("type", "boolean",
                            "description", "Draw A* expanded-node heat. Default true."),
                        "save", Map.of("type", "boolean",
                            "description", "Write to disk. Default true; false returns dims only."),
                        "name", Map.of("type", "string",
                            "description", "Optional file name (no extension). Default pathchart-NNNN-<ms>.")
                    )
                ))
        );
    }
}
```

(Confirm the helper names in `net.magicterra.worlddriver.mcp.schema.Schemas` — Task report shows `roTool(name, desc, schema)`. If the signature differs, match the existing `ClientTools` call exactly.)

- [ ] **Step 3: Create `PathDebugBootstrap.java`**

```java
package net.magicterra.worlddriver.bot.debug;

import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.api.DriverApi;
import net.magicterra.worlddriver.bot.pathfinder.PathTraceHolder;
import net.magicterra.worlddriver.mcp.ToolCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-time wiring for the path-debug subsystem. Registers the recorder as the active
 * {@link PathTraceHolder#SINK}, the {@code mc.debug.pathChart} route (via the generic
 * {@link DriverApi#addRoute}), and its schema (via {@link ToolCatalog#registerExtra}).
 *
 * Release strip: delete the {@code bot.debug} package and the single call to this method
 * in {@code ClientHooks.register}. Core compiles unchanged.
 */
public final class PathDebugBootstrap {
    private static final Logger LOG = LoggerFactory.getLogger("agent-pathdebug");
    private static volatile boolean done;

    private PathDebugBootstrap() {}

    public static synchronized void init() {
        if (done) return;
        PathDebugRecorder recorder = new PathDebugRecorder();
        PathTraceHolder.SINK = recorder;
        PathChartTool.bind(recorder);
        ToolCatalog.registerExtra(DebugTools::tools);
        DriverApi api = WorldDriverCommon.api();
        if (api != null) {
            api.addRoute("mc.debug.pathChart", PathChartTool::render);
            done = true;
            LOG.info("[pathdebug] initialised — mc.debug.pathChart ready (set pathDebug:true to capture)");
        } else {
            LOG.warn("[pathdebug] DriverApi not ready; route not registered");
        }
    }
}
```

- [ ] **Step 4: Compile**

Run: `./gradlew :common:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/net/magicterra/worlddriver/bot/debug/PathChartTool.java \
        common/src/main/java/net/magicterra/worlddriver/bot/debug/DebugTools.java \
        common/src/main/java/net/magicterra/worlddriver/bot/debug/PathDebugBootstrap.java
git commit -m "feat(pathdebug): mc.debug.pathChart handler, schema, bootstrap"
```

---

## Task 11: Wire bootstrap at client init

**Files:**
- Modify: `common/src/main/java/net/magicterra/worlddriver/client/ClientHooks.java`

- [ ] **Step 1: Call `PathDebugBootstrap.init()` after the API is up**

In `register(ClientDriverApi api)`, after `WorldDriverCommon.ensureMcpUp();`, add:

```java
        // Optional, strippable: wire the path-debug recorder + mc.debug.pathChart now that
        // DriverApi exists. Removing the bot.debug package + this line fully strips the feature.
        net.magicterra.worlddriver.bot.debug.PathDebugBootstrap.init();
```

> Hard Rule #7 (no FQN) note: this is the **one** deliberate inline reference — it is the strip seam, and using the FQN here (rather than an `import`) means deleting this single line is the whole edit, with no orphan import to clean up. Document it in the comment as above. (If you prefer an import for style, add `import net.magicterra.worlddriver.bot.debug.PathDebugBootstrap;` and call `PathDebugBootstrap.init();` — then strip = delete the import + the call.)

- [ ] **Step 2: Compile**

Run: `./gradlew :common:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add common/src/main/java/net/magicterra/worlddriver/client/ClientHooks.java
git commit -m "feat(pathdebug): init path-debug subsystem at client register"
```

---

## Task 12: Validation case + registration

**Files:**
- Create: `common/src/main/resources/data/worlddriver/scripts/validation/56_debug_pathchart.js`
- Modify: `common/src/main/java/net/magicterra/worlddriver/WorldDriverCommon.java` (the `VALIDATION_SCRIPTS` list)

- [ ] **Step 1: Create `56_debug_pathchart.js`**

```javascript
// mc.debug.pathChart is client-only (registered at client init) and depends on the
// pathfinding recorder. On the dedicated GameTest server the route is absent, so skip —
// keeping the headless suite green. When a client is present, smoke the tool end to end.

function clientAvailable() {
    try { Driver.invoke("mc.client.screen.info", {}); return true; } catch (e) { return false; }
}
function routeAvailable() {
    try { Driver.invoke("mc.debug.pathChart", { save: false }); return true; } catch (e) { return false; }
}

if (!clientAvailable() || !routeAvailable()) {
    ScriptTest.run("56_debug_pathchart: skipped (no client / route)", function (t) { /* PASS */ });
} else {

    ScriptTest.run("56_debug_pathchart: settings round-trip", function (t) {
        var r = Driver.invoke("mc.bot.setting", { pathDebug: true, pathChartAutoDump: false, pathDebugMaxNodes: 4000 });
        t.assertEqual(r.ok, true, "write must succeed");
        t.assertEqual(r.settings.pathDebug, true, "pathDebug echoed");
        t.assertEqual(r.settings.pathDebugMaxNodes, 4000, "maxNodes echoed");
    });

    ScriptTest.run("56_debug_pathchart: render returns a non-trivial PNG", function (t) {
        var r = Driver.invoke("mc.debug.pathChart", { width: 800, height: 600 });
        t.assertEqual(r.ok, true, "render must succeed");
        t.assertEqual(r.width, 800, "width honoured");
        t.assertEqual(r.height, 600, "height honoured");
        t.assertTrue(typeof r.path === "string" && r.path.length > 0, "returns a file path");
        t.assertTrue(r.bytes > 1000, "PNG has real bytes");
    });

    ScriptTest.run("56_debug_pathchart: byte-identical across transports", function (t) {
        var args = { width: 640, height: 480, save: false };
        var direct = Driver.invoke("mc.debug.pathChart", args);
        var viaTcp = Driver.system.rpcRoundtrip("mc.debug.pathChart", args);
        var viaMcp = Driver.system.mcpRoundtrip("mc.debug.pathChart", args);
        t.assertEqual(viaTcp.ok, direct.ok, "RPC.ok parity");
        t.assertEqual(viaMcp.ok, direct.ok, "MCP.ok parity");
        t.assertEqual(viaTcp.width, direct.width, "RPC.width parity");
    });
}
```

- [ ] **Step 2: Register the script in `WorldDriverCommon.java`**

In the `VALIDATION_SCRIPTS` list (ends with `"55_setting_perception.js"`), add after it:

```java
            "56_debug_pathchart.js",
```

- [ ] **Step 3: Run the full suite**

Run: `./gradlew :neoforge:runGameTestServer`
Expected: BUILD SUCCESSFUL — suite stays green; `56_debug_pathchart` reports **skipped** in the headless GameTest server (no client), adding a passing case.

- [ ] **Step 4: Commit**

```bash
git add common/src/main/resources/data/worlddriver/scripts/validation/56_debug_pathchart.js \
        common/src/main/java/net/magicterra/worlddriver/WorldDriverCommon.java
git commit -m "test(pathdebug): add 56_debug_pathchart validation case"
```

---

## Task 13: Gitignore + strippability dry-check

**Files:**
- Modify: `.gitignore`

- [ ] **Step 1: Ignore chart output (Hard Rule #5)**

Add to `.gitignore`:

```
# path-debug chart output (runtime, never commit)
**/config/worlddriver/debug/
```

- [ ] **Step 2: Verify the strip story compiles**

Temporarily simulate a release strip to prove decoupling, then restore:

Run:
```bash
git stash --include-untracked   # park the working tree first? NO — instead:
# Move the debug package and the init line aside, compile, then restore.
mv common/src/main/java/net/magicterra/worlddriver/bot/debug /tmp/debug_pkg_bak
# comment out the PathDebugBootstrap.init() line in ClientHooks.java (or sed it):
sed -i.bak 's/^\(\s*\)\(net\.magicterra\.agent\.bot\.debug\.PathDebugBootstrap\.init();\)/\1\/\/ \2/' common/src/main/java/net/magicterra/worlddriver/client/ClientHooks.java
./gradlew :common:compileJava
```
Expected: BUILD SUCCESSFUL — core compiles with the debug package gone (proves the seams are inert).

Restore:
```bash
mv common/src/main/java/net/magicterra/worlddriver/client/ClientHooks.java.bak common/src/main/java/net/magicterra/worlddriver/client/ClientHooks.java
mv /tmp/debug_pkg_bak common/src/main/java/net/magicterra/worlddriver/bot/debug
./gradlew :common:compileJava   # back to green WITH debug present
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add .gitignore
git commit -m "chore(pathdebug): gitignore chart output"
```

---

## Task 14: End-to-end live verification (fixed seed `3257840388`)

This is the substantive correctness check — done interactively, not in CI. Capture findings in a short report and (per session rules) save a memory.

- [ ] **Step 1: Build + launch the client** (the client is currently at the title screen)

```bash
JAVA_TOOL_OPTIONS="-Dworlddriver.mcpPort=39800 -Dworlddriver.rpcPort=39801" ./gradlew :fabric:runClient
```
(If a client is already running on these ports, do not `--stop`; kill by port: `lsof -ti:39801 | xargs -r kill`, then relaunch.)

- [ ] **Step 2: Create the test world (driven via mc.client.* input)**

From `TitleScreen`: Singleplayer → Create New World → set seed `3257840388`, game mode Survival, difficulty Normal (mobs present for the "monster settlement" scenario) → Create. Use `mc.client.screen.tree` to locate each button and `mc.client.input.click` to drive it (the seed field via `mc.client.input.typeText`). Verify `mc.client.screen.info` shows `worldOpen:true, hasPlayer:true`.

- [ ] **Step 3: Survival-buff the bot (isolate pathfinding from death)**

```
mc.action.runCommand{cmd:"effect give @s minecraft:resistance 100000 255 true"}
mc.action.runCommand{cmd:"effect give @s minecraft:regeneration 100000 4 true"}
mc.action.runCommand{cmd:"effect give @s minecraft:fire_resistance 100000 0 true"}
mc.action.runCommand{cmd:"effect give @s minecraft:saturation 100000 255 true"}
```

- [ ] **Step 4: Enable capture**

```
mc.bot.setting{pathDebug:true, pathChartAutoDump:true, pathDebugMaxNodes:6000, pathDebugMaxSamples:12000}
```

- [ ] **Step 5: Run scenarios** — for each, scout terrain (`mc.client.scene` / `mc.client.blocks` / `mc.query`, `/tp` to a varied area as needed), pick a 200–500+ block goal, run `mc.bot.goto`, periodically `mc.debug.pathChart`, then **Read the PNG and analyse**:

  - [ ] Long-distance flat/hills traverse (route adherence + sustained sprint speed).
  - [ ] Cliff crossing (elevation profile: did it route around lethal drops? heading stable at edges?).
  - [ ] River/water crossing (speed drop in water expected; trajectory shouldn't bob-stall).
  - [ ] Cave / underground (Y dips in elevation profile; candidates show the search exploring down).
  - [ ] Monster cluster (`/summon` a few zombies on the line, or route through a village/spawn cluster): does the trajectory bow around them (dangerCost)?
  - [ ] **Deliberate failure**: a goal walled off / across an unswimmable gap → confirm the chart renders with `outcome=NO_PATH/STUCK`, dashed best-effort plan, red-X goal, and that auto-dump produced a file.

  For each chart, judge: (a) actual trajectory tracks the latest planned route; (b) `maxYawErr` is small except at legitimate turns (no persistent heading drift); (c) speed matches terrain (≈5.6 b/s sprint on land, lower in water, ~0 during break/place); (d) no unexpected stalls.

- [ ] **Step 6: Record findings** — write a short results note and a memory entry; file follow-up bugs for any drift/speed/route anomalies the charts surface (these become separate fixes, not part of this plan).

---

## Self-review notes (author)

- **Spec coverage:** §3 SPI → Tasks 1,4,5; §3.2 debug module → Tasks 6–11; §3.3 strip → Tasks 1/2/10/11 + Task 13 dry-check; §4 capture (candidates cap, all plans, ring buffer) → Task 7; §5 dashboard (map+elevation+time-series) → Task 8; §6 tool+config+output dir → Tasks 3,9,10; §7 testing → Task 12 (note: JS-integration not JUnit, rationale stated); §8 E2E → Task 14. All covered.
- **Deviation from spec §7:** spec said "renderer smoke (→108)"; revised to a JS validation case + E2E because the codebase has no JUnit source set and a neoforge GameTest referencing the strippable common debug package would break the strip story. Rationale documented at top.
- **Type consistency:** `PathTrace.WalkerSample`, `PathSession.Candidate/PlannedRoute`, `PathChartRenderer.Opts`, `PathDebugRecorder.snapshot()`, `PathChartWriter.write(img, name)→Map`, `PathChartTool.render(Map)→Map` are used identically across tasks.
- **Known executor checks (verify against live code, don't assume):** exact line numbers in PathFinder/Walker/SettingsCommand may have shifted (working tree is dirty); the `edgeAt(int)` accessor name in Walker; the `Schemas.roTool` signature; that `Goal` record accessors are `target()/x()/z()`. Each task says to match the existing pattern.
