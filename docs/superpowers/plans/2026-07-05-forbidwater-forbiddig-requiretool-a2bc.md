# forbidWater / forbidDig / requireTool (Phase A2b-c) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Three per-goto controls: `forbidWater` (route never enters a water cell), `forbidDig` (route never breaks a block — per-intent dual of the global `allowBreak`), and `requireTool` (fail the goto immediately unless the named item is in inventory).

**Architecture:** `forbidWater`/`forbidDig` are `Constraint` records riding the A2a channel (edge-predicate prune in the neighbor loop; `NoBreak` keys off `edge.toBreak.isEmpty()` which is EXACT — a conditional digger like PillarUp stays allowed when its plan doesn't dig). `requireTool` is a verb-level precondition in the goto resolve path (the executor already auto-equips via `Avatar.selectTool`, so presence is the only missing semantic). NO new `Capability` values — the constraint approach obsoletes the spec's SWIM/DIG move tags (more precise, zero move-file edits).

**Tech Stack:** Java 21 multi-loader (`common` + `neoforge` GameTests), the A2a `SearchProfile`/`Constraint`/`resolveConstraints` seams (all landed on master `8914dd5`+).

## Global Constraints

- **Admissibility:** constraints only PRUNE edges; never negative cost. Empty/absent args = byte-identical no-op (returns from `resolveConstraints` unchanged when keys absent).
- **YAGNI:** `Capability` enum stays `{NONE, PARKOUR}`. No SWIM/DIG tags, no equip logic (already exists), no mid-run tool-loss handling (that is C-phase terminator/B supervision territory — document as a limitation in the schema help).
- **Deterministic PLANNER arenas only** (assert `Result.path()`/`goalReached()`); arenas must pin+restore any `BotConfig` global they depend on (batch-stomp lesson) and use fresh coords (~1800+).
- **`forbidWater` semantics:** prune when the DESTINATION foot cell is water (`world.isWater(to)`). Head-cell water and "walking beside water" stay allowed.
- **`requireTool` semantics:** at goto argument-resolve time, if the item id is nowhere in the player inventory → the goto call errors immediately (same error surface as an unknown waypoint); if present → no further effect (executor auto-selects).
- Gametest run: default ports OK (no other tester).

## File Structure

- **Create** `common/.../bot/pathfinder/constraints/NoWater.java`, `NoBreak.java`.
- **Modify** `common/.../bot/GotoGoalResolver.java` — extend `resolveConstraints` (2 new keys) + add `requireTool` check helper.
- **Modify** `common/.../bot/BotApiImpl.java` — invoke the requireTool precondition in the goto handler before process start.
- **Modify** `common/.../mcp/catalog/BotTools.java` — schema + help for the 3 args.
- **Modify** `neoforge/.../AgentGameTestBias.java` — `forbidWaterArena`, `forbidDigArena`.

---

### Task 1: `NoWater` + `NoBreak` constraints, resolver keys, requireTool precondition, schema

**Files:** the four `common` files above.

**Interfaces:**
- Consumes: `Constraint` (`allows(from,to,edge,goal,world)`), `Move.Edge.toBreak` (`List<BlockPos>`, empty when the edge breaks nothing), `WorldView.isWater(BlockPos)`, existing `resolveConstraints(Params)` (A2a), `Params.getBool`, the goto handler in `BotApiImpl` (~line 149-163, `Params p`, `LocalPlayer`).
- Produces: `record NoWater()`, `record NoBreak()` in `...pathfinder.constraints`; `resolveConstraints` honoring `forbidWater`/`forbidDig`; a thrown `IllegalArgumentException("required tool not in inventory: <id>")` from the goto path when `requireTool` is unsatisfied.

- [ ] **Step 1: `NoWater.java`**
```java
package net.magicterra.agent.bot.pathfinder.constraints;

import net.magicterra.agent.bot.Goal;
import net.magicterra.agent.bot.pathfinder.Constraint;
import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

/** Hard "never enter water": prune any move whose destination foot cell is water.
 *  canStandAt treats a water cell as a floor, so plain Walk edges DO route through
 *  water — a move-type gate can't express this; only an edge prune can. */
public record NoWater() implements Constraint {
    @Override
    public boolean allows(BlockPos from, BlockPos to, Move.Edge edge, Goal goal, WorldView world) {
        return !world.isWater(to);
    }
}
```

- [ ] **Step 2: `NoBreak.java`**
```java
package net.magicterra.agent.bot.pathfinder.constraints;

import net.magicterra.agent.bot.Goal;
import net.magicterra.agent.bot.pathfinder.Constraint;
import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

/** Hard "never break a block": prune any edge that plans a dig. Keyed off the
 *  edge's OWN break list, so a conditional digger (PillarUp under an open sky)
 *  stays allowed while its digging variant is pruned — more precise than the
 *  global {@code allowBreak} kill-switch, and per-intent. */
public record NoBreak() implements Constraint {
    @Override
    public boolean allows(BlockPos from, BlockPos to, Move.Edge edge, Goal goal, WorldView world) {
        return edge == null || edge.toBreak.isEmpty();
    }
}
```

- [ ] **Step 3: extend `resolveConstraints`** in `GotoGoalResolver` (after the existing `leashHard` block, same style):
```java
        // forbidWater: true — never route through a water cell (hard prune).
        if (p.getBool("forbidWater")) cs.add(new NoWater());
        // forbidDig: true — never plan a block-breaking edge (per-intent allowBreak-off).
        if (p.getBool("forbidDig")) cs.add(new NoBreak());
```
(Use plain-name imports per AGENTS #7.)

- [ ] **Step 4: `requireTool` precondition.** Add to `GotoGoalResolver` (near `resolveConstraints`):
```java
    /** requireTool:'minecraft:iron_pickaxe' — fail the goto up front unless the item is
     *  in the player inventory. Presence-only: the Walker already auto-equips the best
     *  tool per dig (Avatar.selectTool), and mid-run tool loss is out of scope here. */
    static void checkRequiredTool(Params p, LocalPlayer player) {
        if (!(p.get("requireTool") instanceof String id) || id.isBlank()) return;
        String want = id.contains(":") ? id : "minecraft:" + id;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            var stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty()
                    && net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(want))
                return;
        }
        throw new IllegalArgumentException("required tool not in inventory: " + want);
    }
```
(Verify the file's existing item-id idiom — if `BotUtil`/`GoalResolver` static imports already expose an item-id helper, use it instead of the raw registry call.) Then in `BotApiImpl`'s goto handler, call `GotoGoalResolver.checkRequiredTool(p, player)` right before the `new IntentProcess(...)` line, using the same `player` reference the resolver receives (find it: `grep -n "resolveGoal(p" common/src/main/java/net/magicterra/agent/bot/BotApiImpl.java` — the LocalPlayer var passed there).

- [ ] **Step 5: schema + help in `BotTools.java`** goto block (after the A2a props):
```java
                    .prop("forbidWater", bool())
                    .prop("forbidDig", bool())
                    .prop("requireTool", string())
```
Help lines (match existing tone):
```
  forbidWater  true → never route through water (hard prune; walking beside water stays fine).
  forbidDig    true → never plan a block-breaking edge (per-goto allowBreak-off; a non-digging pillar/parkour stays allowed).
  requireTool  'minecraft:iron_pickaxe' → fail this goto immediately unless the item is in inventory (equip is automatic when digging; mid-run loss is not monitored).
```

- [ ] **Step 6: compile** — `./gradlew :common:compileJava --console=plain` → `BUILD SUCCESSFUL`.
- [ ] **Step 7: commit** — `git add -A && git commit -m "goto: forbidWater/forbidDig constraints + requireTool precondition (A2b-c Task 1)"`

---

### Task 2: deterministic arenas + CHANGELOG

**Files:** Modify `neoforge/.../AgentGameTestBias.java` (add 2 methods), `CHANGELOG.md`.

**Interfaces:** Consumes `NoWater`, `NoBreak`, `SearchProfile`, existing arena idioms (ServerPlayerAvatar/LevelWorldView/`findPath`), `AgentGameTestSupport.maxPathY`, `BotConfig.allowBreak` pin pattern from `digDownYArena`.

- [ ] **Step 1: `forbidWaterArena`.** Coords ~x0=1800. Flat stone lane with a FULL-WIDTH 2-long water strip mid-lane (set the floor cells to water source blocks, lane bordered by stone walls so there is no dry bypass), goal beyond it. Plain plan: must cross (some path node's foot cell is water — assert via a `pathEntersWater` helper looping `world.isWater`; use `level.getBlockState(p).getFluidState()` or reuse `LevelWorldView.isWater(p)` on the same view). Constrained plan `new SearchProfile(List.of(), CapabilityProfile.ALL, List.of(new NoWater()))`: assert `goalReached()==false` (no dry route exists). Pin nothing (no config dependency) but clear-air the region defensively. Log both outcomes; baseline-gate assert (plain must cross, else geometry is wrong).
- [ ] **Step 2: `forbidDigArena`.** Reuse the `digDownYArena` geometry recipe at fresh coords (~x0=1900): solid slab, chamber-free — player on top, `Goal.YLevel` INSIDE the slab (dig-down is the only route; that arena proved plain reaches it). Pin `allowBreak=true` (restore in finally). Plain plan: `goalReached()==true` (baseline). Constrained `List.of(new NoBreak())`: assert `goalReached()==false`. This proves the per-intent dig-off prunes break edges while the global switch stays ON.
- [ ] **Step 3: run headless** — `./gradlew :neoforge:runGameTestServer --console=plain > /tmp/a2bc.log 2>&1` (background), then check `grep -iE "forbidWaterArena|forbidDigArena|failed at" /tmp/a2bc.log`: both log their pass values, neither in a `failed at` line (other flaky executor arenas are known noise). Confirm no prior gametest server is alive first (`ps aux | grep TransformerRuntime` — session.lock collision hangs the run silently).
- [ ] **Step 4: CHANGELOG** `[Unreleased] ### Added`:
```
- **`mc.bot.goto` gains `forbidWater` (never route through water), `forbidDig` (never
  plan a block-breaking edge — per-goto `allowBreak`-off), and `requireTool` (fail fast
  unless the named item is in inventory) via the intent layer's Constraint channel.**
```
- [ ] **Step 5: commit** — `git add -A && git commit -m "gametest: forbidWater/forbidDig arenas + changelog (A2b-c Task 2)"`

---

## Self-Review

**Spec coverage:** forbidWater (SWIM gate in old spec → NoWater constraint, more precise) ✓; requireTool (DIG+item precondition → presence check, equip already automatic) ✓; forbidDig (per-intent allowBreak) ✓. Spec's SWIM/DIG Capability values intentionally NOT added — the plan should note the spec deviation in the Task 2 commit body: constraints subsume the tags precisely.

**Placeholder scan:** Task 1 Step 4 flags the item-id idiom check and the BotApiImpl `player` reference lookup with exact greps. Arena geometry follows two proven recipes (water strip = avoidRegion lane + fluid; dig slab = digDownYArena). No TBDs.

**Type consistency:** `NoWater()`/`NoBreak()` are `Constraint` (5-arg `allows`, matches A2a); `resolveConstraints` already returns `List<Constraint>` consumed by the 4-arg `Intent` (A2a Task 6 wiring — untouched here); `checkRequiredTool(Params, LocalPlayer)` matches the resolver's existing param types (`resolveGoal(Params, LocalPlayer, Map)`).
