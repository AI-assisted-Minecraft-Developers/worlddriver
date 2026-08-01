# Intent Cost-Bias Modifiers + Verb (Phase A4b) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add three per-intent `CostModifier`s (`AvoidRegion`, `PreferYBand`, `LeashAnchor`) that ride the A4a bias channel, wire `mc.bot.goto` to accept `avoid`/`preferY`/`leash` args that build them into the `Intent`, and prove the behavior with a DETERMINISTIC planner arena (the planned path detours around an avoid zone). This is the first visible new capability of the LLM navigation intent layer.

**Architecture:** Each modifier is an immutable record implementing `CostModifier.extraCost(from,to,edge,goal,world) → double` (≥0, admissible). `GotoGoalResolver.resolveBias(Params)` parses the new args into a `List<CostModifier>`; `BotApiImpl` builds `new Intent(goal, bias)`; the A4a plumbing carries the list into every search. Proof is a GameTest that plans with `new PathFinder(w, List.of(avoid))` (A4a overload) and asserts the path avoids the zone — deterministic (A* over a fixed world), sidestepping the executor-arena flakiness that muddied A4a's parity check.

**Tech Stack:** Java 21, NeoForge/Fabric multi-loader (`common` = pathfinder + resolver + schema; `neoforge` = GameTests), Gradle, `LevelWorldView`, `AgentGameTestSupport.runSearch`.

## Global Constraints

- **`CostModifier` admissibility:** every modifier returns `>= 0` (else A* optimality/termination break). Ramps decay to 0 at their boundary; outside the influence region they return 0.
- **Reuse the existing avoid ramp formula verbatim** (from `ClientWorldView` avoidZones): `penalty * (r - dist) / r` for `dist < r`, else 0, where `dist` is Euclidean from the cell centre (`to.x+0.5, to.y, to.z+0.5`) to the zone centre. `AvoidRegion` must match this so per-intent avoid and global `avoidPoints` behave identically.
- **Per-intent, NOT global.** These modifiers live on the `Intent.bias` (cleared when the intent ends). The existing global `BotConfig.avoidZones` / `mc.bot.setting{avoidPoints}` stays untouched for backward-compat; document that `avoid:` on goto is the intent-scoped equivalent (using both stacks both — expected).
- **Proof is a DETERMINISTIC planner arena, never an executor arena** (project memory: executor arenas driving `new Walker()` are run-to-run flaky). Assert on the planned `PathFinder.Result` node sequence, which is deterministic.
- **Acceptance:** the new deterministic arena passes; the full GameTest suite gains no NEW *required* failures attributable to this change (the pre-existing flaky executor arenas are noise — judge by the new arena + whether any changed arena is causally reachable from a planner-cost addition that is INERT unless the goto carries bias args).
- Alternate ports for any run: `JAVA_TOOL_OPTIONS="-Dworlddriver.mcpPort=39810 -Dworlddriver.rpcPort=39811"`.

## File Structure

- **Create** `common/src/main/java/net/magicterra/worlddriver/bot/pathfinder/modifiers/AvoidRegion.java` — ramp-away-from-a-sphere modifier.
- **Create** `common/src/main/java/net/magicterra/worlddriver/bot/pathfinder/modifiers/PreferYBand.java` — penalize cells outside a Y band.
- **Create** `common/src/main/java/net/magicterra/worlddriver/bot/pathfinder/modifiers/LeashAnchor.java` — penalize cells beyond a soft radius from an anchor.
- **Modify** `common/src/main/java/net/magicterra/worlddriver/bot/GotoGoalResolver.java` — add `static List<CostModifier> resolveBias(Params p)`.
- **Modify** `common/src/main/java/net/magicterra/worlddriver/bot/BotApiImpl.java` — build `new Intent(goal, GotoGoalResolver.resolveBias(p))` at the goto site.
- **Modify** `common/src/main/java/net/magicterra/worlddriver/mcp/catalog/BotTools.java` — document + schema the `avoid`/`preferY`/`leash` goto args.
- **Create** `neoforge/src/main/java/net/magicterra/worlddriver/neoforge/AgentGameTestBias.java` — the deterministic detour + preferY planner arenas.

---

### Task 1: `AvoidRegion` modifier

**Files:** Create `common/src/main/java/net/magicterra/worlddriver/bot/pathfinder/modifiers/AvoidRegion.java`

**Interfaces:**
- Produces: `record AvoidRegion(double cx, double cy, double cz, double radius, double penalty) implements CostModifier` — consumed by `resolveBias` (Task 4) and the arena (Task 6).

- [ ] **Step 1: Create the file**

```java
package net.magicterra.worlddriver.bot.pathfinder.modifiers;

import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.pathfinder.CostModifier;
import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

/**
 * Per-intent "route around this sphere" cost: a linear ramp from {@code penalty}
 * at the centre to 0 at {@code radius}, matching the global {@code avoidPoints}
 * ramp in ClientWorldView so intent-scoped and global avoid behave identically.
 * Admissible (>= 0). Cells outside the radius add nothing.
 */
public record AvoidRegion(double cx, double cy, double cz, double radius, double penalty)
        implements CostModifier {
    @Override
    public double extraCost(BlockPos from, BlockPos to, Move.Edge edge, Goal goal, WorldView world) {
        if (radius <= 0 || penalty <= 0) return 0;
        double dx = (to.getX() + 0.5) - cx, dy = to.getY() - cy, dz = (to.getZ() + 0.5) - cz;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return dist < radius ? penalty * (radius - dist) / radius : 0;
    }
}
```

- [ ] **Step 2: Compile** — `./gradlew :common:compileJava --console=plain` → `BUILD SUCCESSFUL`.
- [ ] **Step 3: Commit** — `git add ... && git commit -m "pathfinder/modifiers: AvoidRegion (per-intent route-around ramp, reuses avoidZone formula) (A4b)"`

---

### Task 2: `PreferYBand` modifier

**Files:** Create `common/src/main/java/net/magicterra/worlddriver/bot/pathfinder/modifiers/PreferYBand.java`

**Interfaces:**
- Produces: `record PreferYBand(int yMin, int yMax, double weight) implements CostModifier`.

- [ ] **Step 1: Create the file**

```java
package net.magicterra.worlddriver.bot.pathfinder.modifiers;

import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.pathfinder.CostModifier;
import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

/**
 * Per-intent "prefer this Y band" cost: {@code weight} per block that {@code to}
 * sits OUTSIDE [yMin, yMax]. Inside the band adds nothing. Admissible (>= 0).
 * Backs "walk on the 2nd floor" / "hug the surface". {@code yMin <= yMax}.
 */
public record PreferYBand(int yMin, int yMax, double weight) implements CostModifier {
    @Override
    public double extraCost(BlockPos from, BlockPos to, Move.Edge edge, Goal goal, WorldView world) {
        if (weight <= 0) return 0;
        int y = to.getY();
        int outside = y < yMin ? (yMin - y) : (y > yMax ? (y - yMax) : 0);
        return weight * outside;
    }
}
```

- [ ] **Step 2: Compile** — `./gradlew :common:compileJava --console=plain` → `BUILD SUCCESSFUL`.
- [ ] **Step 3: Commit** — `git add ... && git commit -m "pathfinder/modifiers: PreferYBand (A4b)"`

---

### Task 3: `LeashAnchor` modifier

**Files:** Create `common/src/main/java/net/magicterra/worlddriver/bot/pathfinder/modifiers/LeashAnchor.java`

**Interfaces:**
- Produces: `record LeashAnchor(double ax, double ay, double az, double softRadius, double weight) implements CostModifier`.

- [ ] **Step 1: Create the file**

```java
package net.magicterra.worlddriver.bot.pathfinder.modifiers;

import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.pathfinder.CostModifier;
import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

/**
 * Per-intent "stay near this anchor" cost: {@code weight} per block that {@code to}
 * is BEYOND {@code softRadius} from the anchor. Inside the radius adds nothing.
 * A soft leash — the planner may still leave the radius (e.g. to route around an
 * obstacle) but pays for it, so it hugs the anchor. Admissible (>= 0). Backs
 * "lead player B to the village but don't stray far". (A4b uses a STATIC anchor;
 * a live entity anchor re-evaluated each re-solve is A3.)
 */
public record LeashAnchor(double ax, double ay, double az, double softRadius, double weight)
        implements CostModifier {
    @Override
    public double extraCost(BlockPos from, BlockPos to, Move.Edge edge, Goal goal, WorldView world) {
        if (weight <= 0) return 0;
        double dx = (to.getX() + 0.5) - ax, dy = to.getY() - ay, dz = (to.getZ() + 0.5) - az;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return dist > softRadius ? weight * (dist - softRadius) : 0;
    }
}
```

- [ ] **Step 2: Compile** — `./gradlew :common:compileJava --console=plain` → `BUILD SUCCESSFUL`.
- [ ] **Step 3: Commit** — `git add ... && git commit -m "pathfinder/modifiers: LeashAnchor (static anchor; A4b)"`

---

### Task 4: `GotoGoalResolver.resolveBias` + `BotApiImpl` wiring

**Files:** Modify `common/src/main/java/net/magicterra/worlddriver/bot/GotoGoalResolver.java`, `common/src/main/java/net/magicterra/worlddriver/bot/BotApiImpl.java`

**Interfaces:**
- Consumes: `AvoidRegion`, `PreferYBand`, `LeashAnchor` (Tasks 1-3), `Intent(Goal, List<CostModifier>)` (A4a Task 1).
- Produces: `GotoGoalResolver.resolveBias(Params p) → List<CostModifier>`.

- [ ] **Step 1: Add `resolveBias` to `GotoGoalResolver`.** Read the file first to match its `Params` accessor style (`p.get`, `p.getDouble`, `p.getIntClamped`, and how it reads nested lists — mirror how `SettingsCommand` parses `avoidPoints` at `SettingsCommand.java:704-719`). Add:

```java
    /** Parse the per-intent cost bias args (avoid / preferY / leash) into modifiers
     *  appended to the Intent. Empty when none supplied → plain navigation. */
    static java.util.List<net.magicterra.worlddriver.bot.pathfinder.CostModifier> resolveBias(Params p) {
        java.util.List<net.magicterra.worlddriver.bot.pathfinder.CostModifier> bias = new java.util.ArrayList<>();
        // avoid: [{x,y,z,radius?,penalty?}, ...]
        if (p.get("avoid") instanceof java.util.List<?> zones) {
            for (Object o : zones) {
                if (!(o instanceof java.util.Map<?, ?> m)) continue;
                Double x = num(m.get("x")), y = num(m.get("y")), z = num(m.get("z"));
                if (x == null || y == null || z == null) continue;
                double r = num(m.get("radius")) != null ? num(m.get("radius")) : 8.0;
                double pen = num(m.get("penalty")) != null ? num(m.get("penalty")) : 250.0;
                bias.add(new net.magicterra.worlddriver.bot.pathfinder.modifiers.AvoidRegion(x, y, z, r, pen));
            }
        }
        // preferY: {min,max,weight?}
        if (p.get("preferY") instanceof java.util.Map<?, ?> b) {
            Double lo = num(b.get("min")), hi = num(b.get("max"));
            if (lo != null && hi != null) {
                double w = num(b.get("weight")) != null ? num(b.get("weight")) : 10.0;
                bias.add(new net.magicterra.worlddriver.bot.pathfinder.modifiers.PreferYBand(
                        (int) Math.floor(Math.min(lo, hi)), (int) Math.floor(Math.max(lo, hi)), w));
            }
        }
        // leash: {x,y,z,radius,weight?}
        if (p.get("leash") instanceof java.util.Map<?, ?> l) {
            Double x = num(l.get("x")), y = num(l.get("y")), z = num(l.get("z")), r = num(l.get("radius"));
            if (x != null && y != null && z != null && r != null) {
                double w = num(l.get("weight")) != null ? num(l.get("weight")) : 20.0;
                bias.add(new net.magicterra.worlddriver.bot.pathfinder.modifiers.LeashAnchor(x, y, z, r, w));
            }
        }
        return bias;
    }

    private static Double num(Object o) { return o instanceof Number n ? n.doubleValue() : null; }
```
(If `GotoGoalResolver` already has a numeric coercion helper, reuse it instead of adding `num`. Adjust `p.get(...)` to the file's actual `Params` API — verify by reading the existing `resolveGoal` body.)

- [ ] **Step 2: Wire `BotApiImpl`.** At the goto site, change `startProcess(new IntentProcess(new Intent(goal)));` to `startProcess(new IntentProcess(new Intent(goal, GotoGoalResolver.resolveBias(p))));`. (Leave the elytra ground-fallback and replay sites as plain `new Intent(goal)` — those don't take bias args.)

- [ ] **Step 3: Compile** — `./gradlew :common:compileJava --console=plain` → `BUILD SUCCESSFUL`.
- [ ] **Step 4: Commit** — `git add ... && git commit -m "goto: parse avoid/preferY/leash into the Intent bias (A4b)"`

---

### Task 5: Schema/docs for the new goto args

**Files:** Modify `common/src/main/java/net/magicterra/worlddriver/mcp/catalog/BotTools.java`

**Interfaces:** Consumes nothing new; documents the Task 4 args.

- [ ] **Step 1: Add the three args to the goto tool schema + help text.** Read the goto schema block (near `BotTools.java:271` where `avoidPoints` is documented, and the `.prop(...)` builder near `:415`). Following that exact builder style, add to the goto tool's schema:

```java
                    .prop("avoid", array(object()
                            .prop("x", number()).prop("y", number()).prop("z", number())
                            .prop("radius", number()).prop("penalty", number())))
                    .prop("preferY", object()
                            .prop("min", number()).prop("max", number()).prop("weight", number()))
                    .prop("leash", object()
                            .prop("x", number()).prop("y", number()).prop("z", number())
                            .prop("radius", number()).prop("weight", number()))
```
and to the goto help string, three lines:
```
  avoid    [{x,y,z,radius?,penalty?},...] — per-goto zones to route AROUND (ramp to 0 at radius; dflt radius 8 / penalty 250). Intent-scoped alt to the global avoidPoints setting.
  preferY  {min,max,weight?} — bias the route to stay in a Y band (weight/block outside; dflt 10). E.g. keep to the 2nd floor / hug the surface.
  leash    {x,y,z,radius,weight?} — soft-leash the route near an anchor (weight/block beyond radius; dflt 20). E.g. lead a companion without straying far.
```
(Match the exact `array`/`object`/`number` builder helpers the file already uses; if `number()` needs bounds elsewhere, follow the local convention.)

- [ ] **Step 2: Compile** — `./gradlew :common:compileJava --console=plain` → `BUILD SUCCESSFUL`.
- [ ] **Step 3: Commit** — `git add ... && git commit -m "schema: document goto avoid/preferY/leash args (A4b)"`

---

### Task 6: Deterministic planner arena (detour + preferY proof) + verification

**Files:** Create `neoforge/src/main/java/net/magicterra/worlddriver/neoforge/AgentGameTestBias.java`

**Interfaces:** Consumes `AvoidRegion`, `PreferYBand`, `PathFinder(world, bias)` (A4a), `LevelWorldView`.

- [ ] **Step 1: Write the arena.** Mirror the setup style of `AgentGameTestTerrain` (build a flat floor with `level.setBlockAndUpdate`, `ServerPlayerAvatar.create` for a FakePlayer, `new LevelWorldView(level, fp)`). Two deterministic assertions — plan a straight run, then re-plan with a bias modifier and assert the path changed as required:

```java
package net.magicterra.worlddriver.neoforge;

import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.pathfinder.CostModifier;
import net.magicterra.worlddriver.bot.pathfinder.PathFinder;
import net.magicterra.worlddriver.bot.pathfinder.modifiers.AvoidRegion;
import net.magicterra.worlddriver.bot.pathfinder.modifiers.PreferYBand;
import net.magicterra.worlddriver.bot.world.LevelWorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

import java.util.List;

public final class AgentGameTestBias {

    /** Deterministic: with an AvoidRegion straddling the straight line, the planned
     *  path must keep every node OUTSIDE the zone; without it, the straight path
     *  passes through. Planner is deterministic → clean signal (no executor). */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void avoidRegionDetourArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x0 = 700, z0 = 700, y = 240;
        // 3-wide flat lane along +x from (x0, z0) to (x0+20, z0)
        for (int dx = -1; dx <= 21; dx++)
            for (int dz = -3; dz <= 3; dz++)
                level.setBlockAndUpdate(new BlockPos(x0 + dx, y, z0 + dz), Blocks.STONE.defaultBlockState());
        BlockPos start = new BlockPos(x0, y + 1, z0);
        BlockPos goal = new BlockPos(x0 + 20, y + 1, z0);
        FakePlayer fp = FakePlayerFactory.getMinecraft(level);
        LevelWorldView w = new LevelWorldView(level, fp);

        PathFinder.Result plain = new PathFinder(w).findPath(start, new Goal.Block(goal));
        double zoneX = x0 + 10, zoneZ = z0, zoneY = y + 1, r = 3.0;
        AvoidRegion avoid = new AvoidRegion(zoneX + 0.5, zoneY, zoneZ + 0.5, r, 1000.0);
        PathFinder.Result detour = new PathFinder(w, List.of((CostModifier) avoid))
                .findPath(start, new Goal.Block(goal));

        boolean plainThrough = pathEntersZone(plain, zoneX, zoneY, zoneZ, r);
        boolean detourClear = !pathEntersZone(detour, zoneX, zoneY, zoneZ, r);
        WorldDriverCommon.LOG.info("[avoidRegionDetourArena] plainThrough={} detourClear={} plainLen={} detourLen={}",
                plainThrough, detourClear, plain.path().size(), detour.path().size());
        if (!plainThrough)
            throw new GameTestAssertException("baseline: plain path did NOT pass through the zone — arena geometry wrong");
        if (!detourClear)
            throw new GameTestAssertException("AvoidRegion did NOT detour: a planned node is still inside the zone");
        helper.succeed();
    }

    private static boolean pathEntersZone(PathFinder.Result r, double zx, double zy, double zz, double rad) {
        if (r == null || r.path() == null) return false;
        for (BlockPos p : r.path()) {
            double dx = (p.getX() + 0.5) - zx, dy = p.getY() - zy, dz = (p.getZ() + 0.5) - zz;
            if (Math.sqrt(dx * dx + dy * dy + dz * dz) < rad) return true;
        }
        return false;
    }
}
```
NOTE: verify the real API names before finalizing — read `PathFinder.Result` for the accessor (`path()` vs `path`/`nodes()`), the FakePlayer construction the other arenas use (they use `ServerPlayerAvatar.create(...).fakePlayer()` — prefer that exact call over `FakePlayerFactory` if `LevelWorldView` needs the avatar's player), and that `Goal.Block` is imported. Match `AgentGameTestTerrain`'s proven pattern for FakePlayer + LevelWorldView rather than the sketch above where they differ.

- [ ] **Step 2: Run the new arena headless.**
```bash
JAVA_TOOL_OPTIONS="-Dworlddriver.mcpPort=39810 -Dworlddriver.rpcPort=39811" \
  ./gradlew :neoforge:runGameTestServer --console=plain 2>&1 | tee /tmp/a4b-gametest.log
grep -iE "avoidRegionDetour|required tests (failed|passed)|BUILD (SUCCESSFUL|FAILED)" /tmp/a4b-gametest.log
```
Expected: `[avoidRegionDetourArena] plainThrough=true detourClear=true` and the arena is NOT in the failing set. (Pre-existing flaky executor arenas may still fail — that is noise; the new arena is the A4b signal, and it is deterministic.)

- [ ] **Step 3: CHANGELOG + commit.** Append to `[Unreleased] ### Added`:
```
- **`mc.bot.goto` now accepts per-navigation cost bias — `avoid` (route around
  zones), `preferY` (stay in a Y band), `leash` (soft-stay near an anchor) — via
  the LLM navigation intent layer's `CostModifier` bias channel. Intent-scoped
  (cleared when the goto ends), unlike the global `avoidPoints` setting.**
```
`git add -A && git commit -m "changelog: goto avoid/preferY/leash bias args (A4b)"`

---

## Self-Review

**Spec coverage (design §3.1 CostModifier list + §8 A4):** A4b delivers the soft modifiers (avoid/preferY/leash) on the A4a channel (Tasks 1-3), the verb args (Tasks 4-5), and a deterministic proof (Task 6). Hard constraints (yFloor prune) remain deferred to A2; dynamic entity anchors (live leash/avoid-LOS) remain A3 — the modifiers here take STATIC params, which A3 will re-evaluate per re-solve.

**Reconciliation with existing avoid:** `AvoidRegion` reuses `ClientWorldView`'s exact ramp so per-intent and global `avoidPoints` are consistent; the global mechanism is untouched (Global Constraints).

**Placeholder scan:** The one soft spot is Task 6's exact API names (`PathFinder.Result.path()`, FakePlayer/LevelWorldView construction) — Task 6 Step 1 explicitly instructs verifying against `AgentGameTestTerrain`'s proven pattern before finalizing, rather than trusting the sketch. Tasks 1-5 are concrete.

**Type consistency:** The three records implement `CostModifier.extraCost(BlockPos,BlockPos,Move.Edge,Goal,WorldView)` (A0 signature). `resolveBias` returns `List<CostModifier>` → `new Intent(goal, bias)` (A4a Task 1 ctor) → A4a plumbing → `new PathFinder(w, bias)` (A4a Task 2). Consistent end-to-end.
