# CostModifier Extraction (Phase A0) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract the eight hardcoded per-edge cost taxes in `PathFinder.Search` behind a composable `CostModifier` stack, with byte-identical planning behavior, to unblock the LLM navigation intent layer (phases A1+).

**Architecture:** Introduce a `CostModifier` functional interface. In `PathFinder.Search`, replace the inline sum of the eight private tax methods (`PathFinder.java:700-707`) with an in-order iteration over a `List<CostModifier>` populated with method-reference adapters to those same methods. The two `world.*` costs (`dangerCost`, `directionalCost`) stay inline — they are world properties, not intent-composable taxes. Summation order is preserved exactly so floating-point results are bit-identical; the existing headless GameTest arenas + a pmcs replay diff prove no path diverges.

**Tech Stack:** Java 21, NeoForge/Fabric multi-loader (`common` module holds the pathfinder), Gradle, NeoForge GameTest server (`:neoforge:runGameTestServer`), Python pmcs conformance/replay harness (`scripts/pmcs/`).

## Global Constraints

- **This is a behavior-preserving refactor. NO planning behavior may change.** Acceptance = existing GameTest suite stays green AND archived replay envelopes plan an identical node sequence.
- **Preserve summation order exactly.** The eight taxes must be added in the same order as `PathFinder.java:700-707` (`descendTax, waterCellTax, leafCellTax, padCellTax, vineOverWaterTax, padOverWaterTax, climbOutTax, submergedTax`). Floating-point addition is not associative; reordering can flip a boundary `ng > existing.g - MIN_IMPROVEMENT` comparison.
- **Do not touch the tax method bodies.** Their gating logic (`goal.ignoresY()`, `diveGoal()`, `BotConfig.*` flags, `world.*` predicates) stays verbatim; only the call site changes.
- **Keep `world.dangerCost(npos)` and `world.directionalCost(cur.pos, npos)` inline** at the head of the sum, exactly where they are now. They are out of scope for A0.
- **Acceptance truth is live/replay, never GameTest-green alone** (project memory rule). GameTest green is a necessary regression guard; the replay divergence check is the real gate.
- **Trust only `required tests passed` / `BUILD SUCCESSFUL`** in GameTest output — the `TOTAL:` line masks required failures (project memory rule).

## File Structure

- **Create** `common/src/main/java/net/magicterra/worlddriver/bot/pathfinder/CostModifier.java` — the functional interface. One responsibility: the per-edge extra-cost contract. ~15 lines incl. javadoc.
- **Modify** `common/src/main/java/net/magicterra/worlddriver/bot/pathfinder/PathFinder.java` — add a `List<CostModifier> costModifiers` field to `Search`, populate it once in the `Search` constructor (in tax order), and replace the inline tax sum at lines 700-707 with an in-order loop. The eight private tax methods are unchanged.

No other files change in A0.

---

### Task 1: Introduce the `CostModifier` interface

**Files:**
- Create: `common/src/main/java/net/magicterra/worlddriver/bot/pathfinder/CostModifier.java`

**Interfaces:**
- Produces: `CostModifier.extraCost(BlockPos from, BlockPos to, Move.Edge edge, Goal goal, WorldView world) -> double` — the single-method contract Task 2 iterates over. `goal`/`world` are passed for future (phase A1+) implementers that don't close over `Search`; A0's adapters ignore them and delegate to existing `Search` methods.

- [ ] **Step 1: Create the interface file**

```java
package net.magicterra.worlddriver.bot.pathfinder;

import net.magicterra.worlddriver.bot.Goal;
import net.minecraft.core.BlockPos;

/**
 * A composable per-edge cost contribution added to the A* g-cost when a move
 * enters {@code to} from {@code from}. The A0 refactor extracts the eight
 * hardcoded taxes in {@link PathFinder.Search} behind this interface without
 * changing behavior; later phases (the LLM navigation intent layer) add and
 * remove modifiers per intent (avoid / leash / preferY / water taxes ...).
 *
 * <p><b>Admissibility contract:</b> implementations MUST return {@code >= 0}, or
 * the A* heuristic stops being an underestimate and optimality/termination
 * guarantees break.
 *
 * <p>{@code goal} and {@code world} are supplied so an implementation need not
 * close over a {@link PathFinder.Search}; the A0 adapters ignore them and call
 * the existing {@code Search} tax methods, preserving byte-identical results.
 */
@FunctionalInterface
public interface CostModifier {
    double extraCost(BlockPos from, BlockPos to, Move.Edge edge, Goal goal, WorldView world);
}
```

- [ ] **Step 2: Compile to verify it builds**

Run: `./gradlew :common:compileJava --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add common/src/main/java/net/magicterra/worlddriver/bot/pathfinder/CostModifier.java
git commit -m "pathfinder: add CostModifier interface (A0, no wiring yet)"
```

---

### Task 2: Route the eight taxes through an ordered `CostModifier` stack

**Files:**
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/pathfinder/PathFinder.java` (Search field + constructor + the sum at ~700-707)

**Interfaces:**
- Consumes: `CostModifier` from Task 1.
- Produces: `Search.costModifiers` (a `private final List<CostModifier>`), summed in tax order in the neighbor-expansion loop. Phase A1+ mutates this list to compose intent-specific costs.

- [ ] **Step 1: Add the import and the field**

In `PathFinder.java`, ensure `java.util.List` and `java.util.ArrayList` are imported (add if missing). In the `Search` class body, beside the other `private final` fields (near `goalField`, ~line 181), add:

```java
        /** Ordered stack of per-edge cost taxes, summed in the neighbor loop.
         *  A0 seeds it with the eight legacy taxes IN THEIR ORIGINAL ORDER so
         *  the floating-point sum is bit-identical to the old inline expression;
         *  later phases add/remove modifiers per intent. */
        private final List<CostModifier> costModifiers = new ArrayList<>();
```

- [ ] **Step 2: Populate the stack in the `Search` constructor, in tax order**

At the end of the `Search(BlockPos start, Goal goal, boolean suppressPlace)` constructor (after `goalField` is assigned, ~line 205), append — adapters delegate to the unchanged private methods so behavior is identical:

```java
            // A0: seed the modifier stack with the legacy taxes IN THE EXACT
            // ORDER of the old inline sum (FP addition is not associative).
            costModifiers.add((f, t, e, g, w) -> descendTax(f, t, e));
            costModifiers.add((f, t, e, g, w) -> waterCellTax(t));
            costModifiers.add((f, t, e, g, w) -> leafCellTax(t));
            costModifiers.add((f, t, e, g, w) -> padCellTax(t));
            costModifiers.add((f, t, e, g, w) -> vineOverWaterTax(t));
            costModifiers.add((f, t, e, g, w) -> padOverWaterTax(t));
            costModifiers.add((f, t, e, g, w) -> climbOutTax(f, t));
            costModifiers.add((f, t, e, g, w) -> submergedTax(f, t));
```

- [ ] **Step 3: Replace the inline tax sum with an in-order loop**

Replace exactly this block (`PathFinder.java:698-707`):

```java
                        double ng = cur.g + edge.cost + world.dangerCost(npos)
                                + world.directionalCost(cur.pos, npos)
                                + descendTax(cur.pos, npos, edge)
                                + waterCellTax(npos)
                                + leafCellTax(npos)
                                + padCellTax(npos)
                                + vineOverWaterTax(npos)
                                + padOverWaterTax(npos)
                                + climbOutTax(cur.pos, npos)
                                + submergedTax(cur.pos, npos);
```

with (the two `world.*` costs stay inline and first, then the stack sums in registration order — identical order to the original):

```java
                        double ng = cur.g + edge.cost + world.dangerCost(npos)
                                + world.directionalCost(cur.pos, npos);
                        for (CostModifier mod : costModifiers) {
                            ng += mod.extraCost(cur.pos, npos, edge, goal, world);
                        }
```

- [ ] **Step 4: Compile**

Run: `./gradlew :common:compileJava --console=plain`
Expected: `BUILD SUCCESSFUL`. (If it fails on `descendTax` visibility from a lambda, the lambda captures the enclosing `Search` instance — this is legal for private methods; a failure means a typo in a method name — fix and recompile.)

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/net/magicterra/worlddriver/bot/pathfinder/PathFinder.java
git commit -m "pathfinder: route the 8 edge taxes through the CostModifier stack (A0, byte-identical order)"
```

---

### Task 3: Prove byte-identical planning (headless regression guard + optional replay gate)

**Files:** none modified — this task is verification only.

**Interfaces:** none produced.

**Note on gates:** the pmcs replay harness (`scripts/pmcs/run_case.py`) drives a **live client over WS-RPC** (`ws://127.0.0.1:39801/rpc`, `mc.debug.replay`), so it is subject to the environment's client GL-hang blocker and is NOT reliably headless. The **primary** A0 gate is therefore the headless GameTest suite (Step 1) plus the code-level summation-order guarantee (Global Constraints). The replay divergence diff (Steps 2-3) is a **stronger gate to run when a live client is available**; if the client is blocked, A0 may proceed on Step 1 + order-preservation, and the replay diff is run before the intent layer ships live.

- [ ] **Step 1: Run the headless GameTest suite (primary behavioral regression guard)**

First, on the base commit for comparison, capture the baseline required-count. From the untouched `master` worktree (`.../worlddriver`):

```bash
./gradlew :neoforge:runGameTestServer --console=plain 2>&1 | tee /tmp/a0-baseline-gametest.log
grep -iE "required tests passed|BUILD SUCCESSFUL|BUILD FAILED" /tmp/a0-baseline-gametest.log
```
Record the `required tests passed` count.

Then, from this worktree:

```bash
./gradlew :neoforge:runGameTestServer --console=plain 2>&1 | tee /tmp/a0-gametest.log
grep -iE "required tests passed|BUILD SUCCESSFUL|BUILD FAILED" /tmp/a0-gametest.log
```
Expected: `required tests passed` present with the SAME count as the master baseline, `BUILD SUCCESSFUL` present, no `BUILD FAILED`. Do NOT trust the `TOTAL:` line (it masks required failures). If any pathfinder arena (terrain / water-bank / water-cross / pinch / horizon) fails, the summation order or an adapter is wrong — revert Task 2 Step 3 to the inline sum, diff, fix.

- [ ] **Step 2 (optional, live-client gated): Capture a baseline plan matrix on `master`**

If a live client is available, from the `master` worktree with a running client on port 39801, collect the baseline matrix (omit `--baseline` = collect-only). Read `scripts/pmcs/run_corpus.py --help` first to confirm flags:

```bash
python3 scripts/pmcs/run_corpus.py --corpus config/worlddriver/replays/corpus.json > /tmp/a0-baseline-matrix.json
```
Expected: a per-case matrix JSON (planned paths / telemetry). If the client GL-hang blocker prevents this, skip Steps 2-3 and record the deferral.

- [ ] **Step 3 (optional, live-client gated): Diff the refactor branch against the baseline matrix**

From this worktree with a running client:

```bash
python3 scripts/pmcs/run_corpus.py --corpus config/worlddriver/replays/corpus.json --baseline /tmp/a0-baseline-matrix.json
```
Expected: the divergence table reports ZERO diverging cases — a pure refactor must plan identically. Any divergence is a real regression: stop and investigate before A1.

- [ ] **Step 4: Record the result in the plan and CHANGELOG**

Append to `CHANGELOG.md` `[Unreleased]`: `- pathfinder: internal — extracted the 8 edge taxes behind a composable CostModifier stack (no behavior change), groundwork for the LLM navigation intent layer.` Commit:

```bash
git add CHANGELOG.md
git commit -m "changelog: note CostModifier extraction (A0)"
```

---

## Self-Review

**Spec coverage (against §8 build sequence, item A0):** A0 = "extract `CostModifier` interface; migrate `waterCellTax`/`descendTax`/`dangerCost` behind it; conformance/replay must stay identical." Covered by Task 1 (interface) + Task 2 (migration of the 8 taxes) + Task 3 (conformance/replay identity). Note: the spec named `dangerCost` as a candidate, but §File-Structure of this plan scopes it OUT (it is a `WorldView` property, kept inline) — this is a deliberate YAGNI narrowing; `dangerCost`/`directionalCost` become modifiers only if a later phase needs per-intent weighting. This narrowing is called out in Global Constraints.

**Placeholder scan:** No TBD/TODO. All code blocks are concrete. Task 3 Steps 2-3 reference `scripts/pmcs/run_corpus.py` flags that may differ from the actual CLI — the executor must read `scripts/pmcs/run_corpus.py --help` / `conformance.py` first and adapt the exact flags; the intent (baseline plan snapshot → candidate → exact diff) is fixed. This is the one place the plan cannot pin an exact command without the harness's help text; flagged here rather than papered over.

**Type consistency:** `CostModifier.extraCost(from, to, edge, goal, world)` defined in Task 1 is called with `(cur.pos, npos, edge, goal, world)` in Task 2 Step 3 and adapted with the same 5-arg lambda shape in Task 2 Step 2. Consistent.
