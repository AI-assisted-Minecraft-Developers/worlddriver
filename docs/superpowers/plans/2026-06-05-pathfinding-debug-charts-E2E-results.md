# Pathfinding Debug Charts — E2E Results

**Date:** 2026-06-05 · **Seed:** `3257840388` (Survival, Normal, cheats on) · **Branch:** `feat/pathfinding-debug-charts`

## What was run
Live fabric client (Xvfb headless), fresh seeded world, bot buffed (resistance/regen/fire-res/saturation/water-breathing) so death can't confound pathfinding observation. `mc.bot.setting{pathDebug:true, pathChartAutoDump:true, pathDebugMaxNodes:8000, pathDebugMaxSamples:16000}`. Charts captured with `mc.debug.pathChart` and read back via multimodal vision.

- **Run 1** — `xz(-150,-150)` ~210-block NW traverse: bot crossed from (14,101,-13) and descended to y~62 (real cliff/hill descent). (Exposed the renderer NPE — see Bugs.)
- **Run 2** — `xz(0,-30)` ~145-block NE traverse, climbing back up: `outcome=SUCCESS, plans=4, candidates=7800, samples=686`. Chart `e2e-run2-ne.png`.
- **Run 3** — `pos(0,132,-30)` unreachable height with `allowPlace/allowBreak=false`: `outcome=SUCCESS` via path-exhausted best-effort, `lastPath.goalReached=false`, dashed failed-plan rendering. Chart `e2e-run3-fail.png`.

## Feature verdict — WORKS end to end
- Capture (candidates + every planned route + per-tick trajectory) → AWT dashboard → PNG on disk (`fabric/run/config/worlddriver/debug/`) → multimodal-readable. ✅
- Both manual `mc.debug.pathChart` and `pathChartAutoDump` on terminal. ✅
- Composite dashboard renders all panels on real data: top-down map (candidate heat, planned routes incl. **dashed orange failed plans**, speed-coloured trajectory, start/goal/now markers), elevation profile (Y vs distance — shows real climbs/descents), speed time-series, actual-vs-target heading time-series. ✅
- `mc.debug.pathChart` available + correct across the MCP transport; `mc.bot.setting{pathDebug:...}` round-trips. ✅

## Bugs found by the E2E (fixed, committed)
1. **Renderer NPE** (`ebfb189`) — `plotYaw` used `actual ? (double)x : targetBearing(...)`; the mixed primitive/`Double` ternary auto-unboxed a null `targetBearing()` (no-target sample) → NPE *before* the null guard. No chart rendered until fixed. Split into separate statements.
2. **`mc.bot.setting` schema gap** (`058d5e7`) — the 4 new keys existed in `BotConfig`/`SettingsCommand` but were not declared in the `mc.bot.setting` MCP input schema in `BotTools.java`, so the MCP transport silently dropped them (worked via direct route/script only). Added keys + docs.

## Pathfinding observations surfaced by the charts (NOT feature bugs — these are what the tool is *for*; candidate follow-ups)
- **Heading oscillation** — actual-yaw trace shows a pronounced square-wave swing mid-walk; `maxYawErr` 120–180° on both runs. The body swings rather than holding the (stable) target bearing. Likely the diagonal/strafe re-centre wiggle or a yaw-hysteresis interaction. Worth a focused Walker look.
- **Low / bursty speed** — `avgSpd` ~1.1–1.3 b/s vs sprint ref 5.6, sawtooth profile, occasional >sprint peaks (position jumps / chunk-load). Partly confounded by the headless client (~5–7 fps) and by repath-stall ticks being sampled (esp. run 3 where the goal was unreachable), but the stop-start pattern alongside the heading swing suggests locomotion isn't sustaining smooth sprint on this terrain.
- **Best-effort terminal semantics** — on open terrain a goto rarely reports hard `NO_PATH`/`STUCK`: A* always returns a best-effort segment, the Walker walks it and fires the path-exhausted `ARRIVED→SUCCESS` even when `lastPath.goalReached=false`. So chart `outcome=SUCCESS` can co-exist with `reached=false`; read both. A literal failure outcome only fires when truly boxed in.

## Notes for re-running
- Build/launch: `bash /tmp/launch_client.sh` (Xvfb :99 + matchbox + `:fabric:runClient`, ports 39800/39801). Kill old client by JVM pid (lsof on the port can miss it) before relaunch — never `./gradlew --stop` with a client alive.
- Charts land in `fabric/run/config/worlddriver/debug/` (gitignored).
