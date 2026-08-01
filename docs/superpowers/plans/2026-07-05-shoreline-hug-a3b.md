# Shoreline-Hug Bias — 沿河岸走 (Phase A3b) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** "沿着河岸走" as a composition: `goto <far point/direction> + hugShore:{weight} + forbidWater:true` — a new `ShorelineHug` CostModifier taxes every route node that has NO water nearby, so A* glues the route to the waterline while `NoWater` keeps it dry.

**Architecture:** One new bias citizen riding the existing A4a channel (`CostModifier.extraCost(from,to,edge,goal,world)` already receives `world` — probe `world.isWater` on the 4 cardinal neighbors at `to.y` and `to.y-1`, early-out on first hit). No new machinery: no TargetProvider, no re-solve changes (river bends are followed because leaving the shore band costs `weight` per node; if live shows long bends defeat a far goal, escalation to derived targets is a future phase). Admissible: penalty-only, never negative.

**Tech Stack:** Java 21 multi-loader; A4a bias channel; deterministic planner arenas in `AgentGameTestBias` (NOT executor arenas).

## Global Constraints

- **Admissibility:** `extraCost >= 0` always (CostModifier contract at CostModifier.java — read it).
- **Absent key = byte-identical no-op** (resolveBias returns unchanged when `hugShore` absent).
- **Probe budget:** ≤8 `isWater` probes per node, early-out on first water (blockstate cache absorbs repeats).
- **Water probes at `to.y` AND `to.y-1`** — a bank cell's adjacent water surface usually sits one below the bank foot level.
- Deterministic planner arenas only; fresh coords (~x0=2100); pin+restore any BotConfig dependency; log pass values; baseline-gate asserts (plain path must behave as expected, else geometry error).
- Live validation is the CONTROLLER's job (client relaunch + rpc.py + screen-watch) — subagent tasks stop at arena green.

## File Structure

- **Create** `common/.../bot/pathfinder/modifiers/ShorelineHug.java`
- **Modify** `common/.../bot/GotoGoalResolver.java` — `hugShore` key in `resolveBias`.
- **Modify** `common/.../mcp/catalog/BotTools.java` — schema prop + 沿河岸 recipe help.
- **Modify** `neoforge/.../AgentGameTestBias.java` — `shorelineHugArena`.
- **Modify** `CHANGELOG.md`.

---

### Task 1: ShorelineHug modifier + verb + schema

**Files:** the three `common` files above.

**Interfaces:**
- Consumes: `CostModifier` (5-arg `extraCost`), `WorldView.isWater(BlockPos)`, `resolveBias(Params)` (A4b — read its `avoid`/`preferY` blocks for the parse idiom), BotTools goto schema block.
- Produces: `record ShorelineHug(double weight)` in `...pathfinder.modifiers`; `resolveBias` honoring `hugShore:{weight?}` (also accept bare `hugShore:true` → default weight 30).

- [ ] **Step 1: `ShorelineHug.java`**
```java
package net.magicterra.worlddriver.bot.pathfinder.modifiers;

import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.pathfinder.CostModifier;
import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

/** 沿河岸走: tax every node that has NO water among its 4 cardinal neighbors
 *  (checked at the foot level and one below — a bank cell's adjacent water
 *  surface usually sits one below the bank foot). Routes inside the shoreline
 *  band pay nothing; anything inland pays {@code weight} per node, so with
 *  weight well above the per-node walk cost (10) A* hugs the waterline.
 *  Pair with {@code forbidWater} to stay dry. Penalty-only — admissible. */
public record ShorelineHug(double weight) implements CostModifier {
    @Override
    public double extraCost(BlockPos from, BlockPos to, Move.Edge edge, Goal goal, WorldView world) {
        if (nearWater(to, world)) return 0;
        return weight;
    }

    private static boolean nearWater(BlockPos p, WorldView w) {
        for (int i = 0; i < 4; i++) {
            int dx = (i == 0) ? 1 : (i == 1) ? -1 : 0;
            int dz = (i == 2) ? 1 : (i == 3) ? -1 : 0;
            BlockPos side = p.offset(dx, 0, dz);
            if (w.isWater(side) || w.isWater(side.below())) return true;
        }
        return false;
    }
}
```

- [ ] **Step 2: `resolveBias` key** (after the `preferY` block, same leniency style):
```java
        // hugShore: true|{weight} — 沿河岸走: tax nodes with no adjacent water so the
        // route glues to the waterline (pair with forbidWater to stay dry).
        Object hs = p.get("hugShore");
        if (hs instanceof Boolean b && b) bias.add(new ShorelineHug(30.0));
        else if (hs instanceof Map<?, ?> m) bias.add(new ShorelineHug(Params.toDouble(m.get("weight"), 30.0)));
```
(Adapt `Params.toDouble` to the file's actual helper idiom — match how the leash block reads `weight`.)

- [ ] **Step 3: schema + help.** BotTools goto block: `.prop("hugShore", object().prop("weight", number()))` — note in the desc that bare `true` also works if the schema layer allows union; if it must be one type, keep object-only and document `{}` as "defaults". Help line (match tone):
```
  hugShore  {weight:30} → 沿着河岸走 recipe: goto a far point (or direction) + hugShore + forbidWater:true — the route sticks to the waterline and stays dry; weight ≫ 10 (per-node walk cost) pins it to the bank.
```

- [ ] **Step 4: compile + commit** — `./gradlew :common:compileJava --console=plain` → BUILD SUCCESSFUL; `git add -A && git commit -m "goto: hugShore shoreline-affinity bias — 沿河岸走 recipe (A3b Task 1)"`

---

### Task 2: deterministic `shorelineHugArena` + CHANGELOG

**Files:** `neoforge/.../AgentGameTestBias.java`, `CHANGELOG.md`.

**Interfaces:** Consumes `ShorelineHug`, `NoWater`, `SearchProfile`, `CapabilityProfile.ALL`, the arena idiom of `avoidRegionDetourArena`/`forbidWaterArena` in the same file (ServerPlayerAvatar → LevelWorldView → `new PathFinder(w, List.of(mod))` or profile ctor → findPath → assert on `Result.path()`).

- [ ] **Step 1: geometry.** Coords ~x0=2100, y=240. Stone slab 40×14 (x0-1..x0+38, z0-9..z0+4). Travel along +x: start (x0, y+1, z0), goal (x0+36, y+1, z0). WATER region south of a receding shoreline: for each x column, water fills z0-9..shore(x) where `shore(x) = z0-1` for x within 6 of either end, and `shore(x) = z0-7` for the middle third (linear-ish steps between are fine — a simple 3-plateau step function is enough). Result: near the ends the bank (z0) touches water (z0-1); in the middle the water edge sits at z0-7, so the straight z0 lane is ~6 from water there, while the hugging route must dip south to z≈z0-6. Bedrock walls on the north edge (z0+4) and beyond-south (z0-9) rows to kill stray detours. Water as source blocks; the slab's stone floor beneath keeps it contained (1-deep water on stone, same pattern as forbidWaterArena's pool).
- [ ] **Step 2: plans + asserts.**
```java
PathFinder.Result plain = new PathFinder(w).findPath(start, new Goal.Block(goal));
SearchProfile hug = new SearchProfile(List.of(new ShorelineHug(30.0)),
        CapabilityProfile.ALL, List.of(new NoWater()));
PathFinder.Result hugged = new PathFinder(w, 60000, 0, hug).findPath(start, new Goal.Block(goal));
```
(Check the actual 4-arg ctor signature in PathFinder — use whichever public ctor takes a SearchProfile; `new PathFinder(w, profile)` may exist.) Asserts, each with a log line first:
  - baseline gates: both `goalReached()`.
  - `plain` must CUT INLAND: no plain node has z < z0-2 (it stays on the straight lane) — if this fails the geometry is wrong (log both paths' min-z).
  - `hugged` must DIP: at least one node with z <= z0-5.
  - `hugged` must stay in the band: EVERY hugged node within 2 of water — assert via a helper `distToWaterLE(node, 2)` probing the 5×5 ring at node.y and node.y-1; log the worst offender if violated.
- [ ] **Step 3: run headless.** Confirm no neoforge gametest server alive (`ps aux | grep TransformerRuntime` — the ONE fabric live client does NOT collide; only a `runGameTestServer` process does). Launch `./gradlew :neoforge:runGameTestServer --console=plain > /tmp/a3b-arena.log 2>&1 &` then POLL the log with repeated short greps (`grep -iE "shorelineHugArena|failed at" /tmp/a3b-arena.log`) every ~60s via separate Bash calls — do NOT suspend on a monitor. Full run ~15 min. The arena must pass; pre-existing flaky executor arenas (vine/scene/walker*) are known noise.
- [ ] **Step 4: CHANGELOG** `[Unreleased] ### Added`: "`mc.bot.goto` gains `hugShore` (shoreline-affinity bias) — 沿着河岸走 = goto far point + hugShore + forbidWater."
- [ ] **Step 5: commits** — arena: `git commit -m "gametest: shorelineHugArena — bank-hugging route vs inland shortcut (A3b Task 2)"`; changelog: `git commit -m "changelog: goto hugShore 沿河岸走 (A3b)"`.

---

## Self-Review

**Coverage:** 沿河岸走 scenario ✓ (recipe = far goal + hugShore + forbidWater); river bends followed by the band tax (documented escalation path if live disproves) ✓; no other A3b scope.
**Placeholders:** Task 1 Step 2/3 flag the Params-idiom and schema-union adaptations explicitly; Task 2 Step 2 flags the ctor-signature check. Complete code otherwise.
**Types:** `ShorelineHug(double)` consistent (Task 1 Step 1 ↔ Task 2 Step 2); `NoWater` from A2b-c; `SearchProfile` 3-arg record from A2a.
