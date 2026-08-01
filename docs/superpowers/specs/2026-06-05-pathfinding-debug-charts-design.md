# Pathfinding Debug Charts — Design

**Date:** 2026-06-05
**Status:** Approved (pending spec review)
**Author:** worlddriver maintainer

## 1. Problem & Goals

Debugging the A* pathfinder + Walker today relies on `walkerDebug` text logs and the
numeric `lastPath` stats (`expanded/ms/goalReached/finalCost/pathLen`). That is not
enough to answer the questions that actually matter during long-distance traversal:

- Did the bot **follow the planned route**, or drift off it?
- Is the **heading (yaw) drifting** from the target heading?
- Is **speed** correct (sprinting on land, slow in water), or is it stalling/bobbing?
- On **failure**, where did it break — no path, stuck, timeout, cancel?

**Goal:** render an image "dashboard" per goto session that overlays the three routes
(search **candidates**, each **planned** route, the **actual** trajectory) plus
time-series of **speed** and **heading**, and a vertical **elevation profile** for
cliffs/caves/hills. Write it to disk as PNG so it can be read back with multimodal
vision for analysis.

**Non-goals:** in-world rendered overlays; live streaming; a general charting library.

## 2. Key Decisions (resolved during brainstorming)

| Decision | Choice |
| --- | --- |
| Rendering approach | **Off-screen AWT** (`BufferedImage`/`Graphics2D`), headless-safe |
| Delivery | **Write PNG to disk**, tool returns the file path (read back via vision) |
| Views | **Composite dashboard**: top-down map + elevation profile + time-series |
| Coupling | **Strippable**: tiny core SPI + NOOP, all heavy code isolated in `bot.debug` |
| Failure capture | Charts must render mid-walk and on **any terminal outcome** |
| E2E world | **Fixed seed** `3257840388` (recorded), survival, bot given survival buffs |

## 3. Architecture

### 3.1 Core SPI (in `net.magicterra.worlddriver.bot.pathfinder`)

A minimal, dependency-free seam so the core never references debug code:

```java
public interface PathTrace {
    void onSearchBegin(BlockPos start, BlockPos goal);
    void onNodeExpanded(BlockPos pos, double g);
    void onSearchResult(List<BlockPos> path, List<Move.Edge> edges,
                        boolean goalReached, int expanded, long ms, double finalCost);
    void onWalkerTick(WalkerSample s);   // s is a small immutable carrier
    void onTerminal(Outcome outcome, String reason);

    enum Outcome { SUCCESS, NO_PATH, STUCK, TIMEOUT, CANCELLED, ERROR }

    PathTrace NOOP = /* all methods empty */;
}
```

A holder exposes the active sink:

```java
public final class PathTraceHolder {
    public static volatile PathTrace SINK = PathTrace.NOOP;
}
```

`WalkerSample` is a small record carrying the per-tick truth:
`(long tick, double x,y,z, float yawActual, float yawTarget, double speed,
  int stepIndex, String moveType, boolean onGround, boolean inWater)`.
It lives in the pathfinder/movement core (it is plain data, no debug dependency).

**Call sites (the only core edits):**
- `PathFinder.Search`: `onSearchBegin` at start, `onNodeExpanded` per expansion,
  `onSearchResult` when a `Result` is produced (success *and* early-exit/no-path).
- `Walker.tick`: `onWalkerTick` each tick while a goal is active; `onTerminal` when the
  goal resolves (reached, given up, cancelled) — including the partial/failed paths.

When `SINK == NOOP` these are empty virtual calls (JIT-elided): **zero overhead** in
release. When a recorder is registered but `pathDebug == false`, the recorder's methods
early-return: still negligible.

### 3.2 Debug module (in `net.magicterra.worlddriver.bot.debug`, self-contained)

- `PathDebugRecorder implements PathTrace` — accumulates the current goto session into
  in-memory buffers (see §4). Gated by `BotConfig.pathDebug`.
- `PathChartRenderer` — **pure function** `(SessionSnapshot, RenderOpts) -> BufferedImage`.
  No I/O, no game state; fully unit-testable headless.
- `PathChartWriter` — encodes the image with `ImageIO` and writes
  `config/worlddriver/debug/pathchart-<seq>.png`; returns the absolute path.
- `DebugTools` — MCP catalog exposing `mc.debug.pathChart`.
- `PathDebugBootstrap.init()` — registers the recorder as `PathTraceHolder.SINK` and
  adds `DebugTools` to the tool catalog. Called once at mod init.

### 3.3 Stripping for release (documented procedure)

1. Delete the `net.magicterra.worlddriver.bot.debug` package.
2. Remove the single `PathDebugBootstrap.init()` call from mod init.
3. Remove the single `DebugTools` registration line (guarded — see §6).

Core still compiles: it retains only the tiny `PathTrace` interface + `NOOP` +
`PathTraceHolder` + `WalkerSample`, all inert. No behavioural change.

## 4. Data Capture

`PathDebugRecorder` keeps one **session**, reset only when a **new goto goal** begins
(NOT on failure/cancel — so failure forensics survive):

- **Candidates**: every `onNodeExpanded` appends `(BlockPos, g)`, capped at
  `BotConfig.pathDebugMaxNodes` (default 4000). Over the cap → uniform reservoir-style
  downsampling, so the spatial spread is preserved without unbounded memory.
- **Planned routes**: every `onSearchResult` stores a
  `PlannedRoute{path, edges, stats, repathIndex, goalReached}`. A long goto repaths
  several times — **all** are retained, so route evolution is visible. Early-exit
  best-effort partial paths (the PathFinder COEFFICIENTS backoff) are stored too and
  flagged `goalReached=false`.
- **Actual trajectory**: `onWalkerTick` appends a `WalkerSample` to a ring buffer capped
  at `BotConfig.pathDebugMaxSamples` (default 6000 ticks ≈ 5 min; oldest dropped).
- **Outcome**: `onTerminal` records the final `Outcome` + reason string.

Thread-safety: writes come from the pathfinder/walker thread(s); the renderer takes an
immutable snapshot (copy-on-render) so reads never tear.

## 5. Rendering — composite dashboard

Single PNG, default 1280×960 (overridable). Three regions + header:

**Header (text):** goal, outcome, lastError, pathLen, finalCost, expanded, ms,
goalReached, repath count, duration, avg/peak speed, max |yaw error|, seed (if known).

**Main — top-down map (X–Z):**
- Candidate nodes: faint heat dots colored by `g` (or expansion order).
- Planned routes: polylines — latest plan **bold**, earlier repaths thin/translucent;
  failed/partial plans **dashed**.
- Actual trajectory: polyline **color-coded by speed**.
- Markers: start (green), goal (red), current/last pos (cyan), **unreachable goal = red X**,
  **stuck point = orange X**.
- Heading arrows every N samples: solid = actual yaw, dashed = target yaw (drift visible).
- Auto-fit bounds to the union of all three layers + padding; `blocksPerPixel` overridable.

**Right/secondary — elevation profile (Y vs cumulative horizontal distance):**
- Planned Y and actual Y as two lines → cliffs (steep), caves (dips), hills.

**Bottom — time-series strip:**
1. Speed (blocks/s) vs time + reference lines (sprint ≈5.6, walk ≈4.3, water slower).
2. Target yaw vs actual yaw (two lines) + shaded |error| → directly answers "heading drift".
3. Progress sparkline: stepIndex / stuckTicks.

## 6. Tools & Config

**New MCP tool** `mc.debug.pathChart`:
```
mc.debug.pathChart{ width?:int, height?:int, blocksPerPixel?:number,
                    includeCandidates?:bool=true, save?:bool=true }
  -> { path:string, outcome:string, stats:{...summary...} }
```
Renders the current session snapshot and (by default) writes it to disk. Callable any
time — mid-walk, after success, or after failure.

**`BotConfig` additions** (static volatile, persisted, exposed via `mc.bot.setting`):
- `pathDebug` (bool, default **false**) — master capture gate.
- `pathDebugMaxNodes` (int, default 4000).
- `pathDebugMaxSamples` (int, default 6000).
- `pathChartAutoDump` (bool, default false) — auto-write a chart on **any** terminal
  outcome (success and failure alike).

`SettingsCommand.apply` learns these keys (mirroring existing scalar/bool handling).

**Output dir:** `config/worlddriver/debug/`, created on demand. Files
`pathchart-<seq>.png` with a monotonic counter (timestamp via
`System.currentTimeMillis()` is fine in mod code).

**Catalog registration:** `PathDebugBootstrap` adds `DebugTools` to the catalog. The add
is guarded so that removing the debug package (release strip) cannot break
`ToolCatalog` — the core catalog list never names a debug class directly.

## 7. Testing

- **Keep GameTest 107/107 green.** No core behaviour changes when debug is disabled.
- **Add 1 renderer smoke test (→ 108):** feed `PathChartRenderer` synthetic data
  (a fabricated `SessionSnapshot` with candidates, two planned routes incl. a failed one,
  a trajectory with a yaw drift and a speed dip) → assert a non-empty `BufferedImage`,
  and that `PathChartWriter` produces a readable PNG on disk. Pure/headless — no terrain.
- **JS validation add:** after a short `goto`, call `mc.debug.pathChart` and assert a
  `path` is returned and the file exists.

## 8. End-to-End Test Plan (fixed seed `3257840388`)

1. From the **title screen**, create a new survival world, seed `3257840388`; record the
   seed in memory.
2. Survival-buff the bot via `runCommand`: `resistance 255`, `regeneration`,
   `fire_resistance` (mobs remain on the map so "monster settlement" avoidance and
   `dangerCost` behaviour are still exercised) — prevents death from confounding
   pathfinding observation.
3. Scout with `mc.client.scene` / `mc.client.blocks` / `mc.query` (and `/tp` as needed)
   to pick **200–500+ block** goals that cross **cliffs, rivers, caves, hills, and mob
   clusters**.
4. `mc.bot.setting{pathDebug:true}`; run `mc.bot.goto`; periodically call
   `mc.debug.pathChart`; **Read the PNG → multimodal analysis** of route adherence, yaw
   drift, speed anomalies. Also exercise a deliberately hard/unreachable goal to verify
   the **failure chart**.
5. Iterate: log findings; file follow-up fixes for any drift/speed/route bugs surfaced.

## 9. Risks / Open Items

- Candidate cap downsampling must preserve spatial spread (reservoir sample over the cap).
- Ring buffer for very long walks: 6000 ticks default; raise via setting for ultra-long.
- AWT in the MC/NeoForge JVM: `Screenshots.java` already uses `Graphics2D`/`ImageIO`, so
  the toolchain is proven headless here.
- Yaw "target" at a given tick must be the value Walker actually aimed at that tick
  (capture inside `Walker.tick` after target computation, before/at key write).
