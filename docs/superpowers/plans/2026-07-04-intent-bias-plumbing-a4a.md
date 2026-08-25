# Intent Cost-Bias Plumbing (Phase A4a) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Thread a per-intent `List<CostModifier>` bias from the `Intent` through the `Walker` into every `PathFinder.Search` it runs, appended to the A0 `costModifiers` stack — as a byte-identical no-op (the `goto` verb produces an EMPTY bias in A4a), so the invasive `Walker` plumbing is proven safe before any behavior (A4b) rides it.

**Architecture:** Bias travels on the `PathFinder` constructor. `PathFinder` gains a `List<CostModifier> bias` field (existing ctors default it empty); `Search` appends it to its `costModifiers` after the eight legacy taxes. The `Walker` holds a `bias` field set via `setBias(...)`; its six `new PathFinder(world...)` search-construction sites pass `, bias`. `IntentProcess` sets `walker.setBias(intent.bias())`. With an empty bias everywhere, zero extra cost is summed → planning is bit-identical.

**Tech Stack:** Java 21, NeoForge/Fabric multi-loader (`common` module), Gradle, NeoForge GameTest server (`:neoforge:runGameTestServer`).

## Global Constraints

- **A4a is a byte-identical no-op refactor.** The `goto` verb produces no modifiers in A4a; bias is empty at every call site, so the A* g-cost sum is unchanged. Behavior may not change.
- **Bias is appended AFTER the eight legacy taxes** in `Search.costModifiers`. Order among the appended (empty in A4a) modifiers is irrelevant to correctness; never reorder or interleave with the legacy taxes.
- **Admissibility:** bias modifiers are `CostModifier`s and MUST return `>= 0` (already the interface contract from A0).
- **Do not change any tax logic, the two inline `world.*` costs, or Walker navigation logic** — only add the bias field/param and the six uniform `, bias` additions.
- **Baseline was invalidated by the master sync.** §87 flipped 7 executor flags default-ON; the old failing-set reference (measured @9a0b045) is stale. Task 4 RE-MEASURES the baseline on the current tip before asserting parity.
- **Acceptance = live/replay truth; trust only `required tests passed`/`BUILD SUCCESSFUL`; the `TOTAL:` line masks required failures** (project memory). Alternate ports for any run: `JAVA_TOOL_OPTIONS="-Dworlddriver.mcpPort=39810 -Dworlddriver.rpcPort=39811"`.

## File Structure

- **Modify** `common/src/main/java/net/magicterra/worlddriver/bot/process/Intent.java` — add `List<CostModifier> bias` (default empty) + `bias()` accessor.
- **Modify** `common/src/main/java/net/magicterra/worlddriver/bot/pathfinder/PathFinder.java` — `bias` field, two ctor overloads, `Search` ctor appends bias.
- **Modify** `common/src/main/java/net/magicterra/worlddriver/bot/movement/Walker.java` — `bias` field + `setBias`; the six `new PathFinder(world...)` sites pass `, bias`.
- **Modify** `common/src/main/java/net/magicterra/worlddriver/bot/process/IntentProcess.java` — `walker.setBias(intent.bias())` in the constructor.

---

### Task 1: `Intent` carries a bias list

**Files:** Modify `common/src/main/java/net/magicterra/worlddriver/bot/process/Intent.java`

**Interfaces:**
- Produces: `Intent(Goal target)` (unchanged, bias defaults empty) + `Intent(Goal target, List<CostModifier> bias)` + `List<CostModifier> bias()` — consumed by `IntentProcess` (Task 4).

- [ ] **Step 1: Edit `Intent.java`** to this (keeps the existing single-arg ctor working; adds a bias-carrying ctor + accessor):

```java
package net.magicterra.worlddriver.bot.process;

import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.pathfinder.CostModifier;

import java.util.List;

/**
 * The declarative unit of navigation the {@link IntentProcess} interprets. Holds
 * the target {@link Goal} and a per-intent {@code bias} — a list of
 * {@link CostModifier}s appended to the pathfinder's cost stack for THIS intent
 * (avoid a region, prefer a Y band, leash to an anchor). A4a threads the (empty)
 * bias through; A4b adds the modifiers and the verb args that build them. Later
 * phases add a capability profile, hard constraints, terminators, and the
 * mutable-goal {@code amend} operation.
 */
public final class Intent {
    private final Goal target;
    private final List<CostModifier> bias;

    public Intent(Goal target) {
        this(target, List.of());
    }

    public Intent(Goal target, List<CostModifier> bias) {
        if (target == null) throw new IllegalArgumentException("intent target is null");
        this.target = target;
        this.bias = (bias == null) ? List.of() : List.copyOf(bias);
    }

    /** The A* goal this intent currently converges on. */
    public Goal target() {
        return target;
    }

    /** Per-intent cost modifiers appended to the pathfinder stack. Empty = plain navigation. */
    public List<CostModifier> bias() {
        return bias;
    }
}
```

- [ ] **Step 2: Compile** — `./gradlew :common:compileJava --console=plain` → `BUILD SUCCESSFUL`.
- [ ] **Step 3: Commit** — `git add ... && git commit -m "process: Intent carries a per-intent cost bias list (A4a, default empty)"`

---

### Task 2: `PathFinder` accepts a bias list; `Search` appends it

**Files:** Modify `common/src/main/java/net/magicterra/worlddriver/bot/pathfinder/PathFinder.java`

**Interfaces:**
- Consumes: `CostModifier` (A0), `List` (import).
- Produces: `PathFinder(WorldView, List<CostModifier> bias)` and `PathFinder(WorldView, int maxNodes, long maxMs, List<CostModifier> bias)` — consumed by the Walker sites (Task 3). Existing ctors delegate with an empty bias.

- [ ] **Step 1: Add the `bias` field + overloads.** Ensure `java.util.List` is imported. Replace the two existing constructors:

```java
    public PathFinder(WorldView world) {
        this(world,
                BotConfig.pathfinderMaxNodes,
                BotConfig.pathfinderMaxMs);
    }
    public PathFinder(WorldView world, int maxNodes, long maxMs) {
        this.world = world;
        this.maxNodes = maxNodes;
        this.maxMs = maxMs;
    }
```
with (add the field near the other final fields, e.g. beside `world`):

```java
    /** Per-intent cost modifiers appended to each Search's stack after the legacy
     *  taxes (A4a: the LLM navigation intent layer's bias channel). Empty for a
     *  plain search. */
    private final java.util.List<CostModifier> bias;

    public PathFinder(WorldView world) {
        this(world, BotConfig.pathfinderMaxNodes, BotConfig.pathfinderMaxMs);
    }
    public PathFinder(WorldView world, java.util.List<CostModifier> bias) {
        this(world, BotConfig.pathfinderMaxNodes, BotConfig.pathfinderMaxMs, bias);
    }
    public PathFinder(WorldView world, int maxNodes, long maxMs) {
        this(world, maxNodes, maxMs, java.util.List.of());
    }
    public PathFinder(WorldView world, int maxNodes, long maxMs, java.util.List<CostModifier> bias) {
        this.world = world;
        this.maxNodes = maxNodes;
        this.maxMs = maxMs;
        this.bias = (bias == null) ? java.util.List.of() : bias;
    }
```
(If `world`/`maxNodes`/`maxMs` are declared `final`, keep them final — all constructors funnel to the 4-arg one which assigns each exactly once. If they are non-final, leave them as-is.)

- [ ] **Step 2: Append bias in the `Search` constructor.** At the END of the `Search(...)` constructor — immediately AFTER the block that seeds the eight legacy taxes into `costModifiers` (the `costModifiers.add((f,t,e,g,w) -> ...)` lines from A0) — add:

```java
            // A4a: append this search's per-intent bias AFTER the legacy taxes.
            // Empty for a plain search → byte-identical to the pre-A4a stack.
            costModifiers.addAll(bias);
```
(`Search` is an inner class of `PathFinder`, so `bias` resolves to the enclosing `PathFinder.this.bias`.)

- [ ] **Step 3: Compile** — `./gradlew :common:compileJava --console=plain` → `BUILD SUCCESSFUL`.
- [ ] **Step 4: Commit** — `git add ... && git commit -m "pathfinder: PathFinder accepts a per-search bias list, appended after legacy taxes (A4a)"`

---

### Task 3: `Walker` holds + forwards the bias; `IntentProcess` sets it

**Files:** Modify `common/src/main/java/net/magicterra/worlddriver/bot/movement/Walker.java`, `common/src/main/java/net/magicterra/worlddriver/bot/process/IntentProcess.java`

**Interfaces:**
- Consumes: `PathFinder(world, bias)` / `PathFinder(world, maxNodes, maxMs, bias)` (Task 2), `Intent.bias()` (Task 1).
- Produces: `Walker.setBias(List<CostModifier>)`.

- [ ] **Step 1: Add the field + setter to `Walker`.** Ensure `java.util.List` and `net.magicterra.worlddriver.bot.pathfinder.CostModifier` are imported. Beside the other private fields, add:

```java
    /** Per-intent cost bias forwarded to every PathFinder this Walker builds (A4a).
     *  Empty = plain navigation; IntentProcess sets it from the Intent. */
    private java.util.List<CostModifier> bias = java.util.List.of();

    /** Set the per-intent cost bias for subsequent searches. Null → empty. */
    public void setBias(java.util.List<CostModifier> b) {
        this.bias = (b == null) ? java.util.List.of() : b;
    }
```

- [ ] **Step 2: Pass `bias` at the six search-construction sites.** Locate each by its `new PathFinder(world...)` text (line numbers approximate) and add the bias argument:
  - `new PathFinder(world).newSearch(searchFoot, goal);` → `new PathFinder(world, bias).newSearch(searchFoot, goal);`
  - `new PathFinder(world).newSearch(commitEnd, goal);` (the eager-precompute site) → `new PathFinder(world, bias).newSearch(commitEnd, goal);`
  - `new PathFinder(world).newSearch(foot, goal, true);` → `new PathFinder(world, bias).newSearch(foot, goal, true);`
  - `new PathFinder(world).newSearch(commitEnd, goal);` (the continuation site in the splice block) → `new PathFinder(world, bias).newSearch(commitEnd, goal);`
  - `new PathFinder(world).newSearch(commitEnd, goal);` (the pinch/escape site) → `new PathFinder(world, bias).newSearch(commitEnd, goal);`
  - `new PathFinder(world, BotConfig.pathfinderQuickNodes, QUICK_MAX_MS).newSearch(foot, goal);` → `new PathFinder(world, BotConfig.pathfinderQuickNodes, QUICK_MAX_MS, bias).newSearch(foot, goal);`

  There are exactly SIX `new PathFinder(world` occurrences. After editing, verify: `grep -c "new PathFinder(world)" common/src/main/java/net/magicterra/worlddriver/bot/movement/Walker.java` must be `0` (every bare no-bias construction is gone) and `grep -c "new PathFinder(world, bias)\|QUICK_MAX_MS, bias)" common/src/main/java/net/magicterra/worlddriver/bot/movement/Walker.java` accounts for all six.

- [ ] **Step 3: Set the bias in `IntentProcess`.** In the `IntentProcess(Intent intent)` constructor, after `walker.setGoal(intent.target());` add `walker.setBias(intent.bias());`. Import `net.magicterra.worlddriver.bot.pathfinder.CostModifier` only if referenced (it is not directly — `intent.bias()` returns the list; no new import needed).

- [ ] **Step 4: Compile** — `./gradlew :common:compileJava --console=plain` → `BUILD SUCCESSFUL`.
- [ ] **Step 5: Commit** — `git add ... && git commit -m "walker: forward a per-intent cost bias to every search; IntentProcess sets it (A4a)"`

---

### Task 4: Re-baseline + prove byte-identical (headless GameTest)

**Files:** none modified except CHANGELOG.

- [ ] **Step 1: Re-measure the baseline failing-set on the current tip's PARENT.** The old @9a0b045 reference is stale (master §87 flag-flips). Establish the fresh reference from the commit just before A4a (the A1 tip = the branch HEAD before Task 1 of this plan; record it as `A4A_BASE`). From a clean worktree at `A4A_BASE`:
```bash
JAVA_TOOL_OPTIONS="-Dworlddriver.mcpPort=39820 -Dworlddriver.rpcPort=39821" \
  ./gradlew :neoforge:runGameTestServer --console=plain 2>&1 | tee /tmp/a4a-baseline.log
grep -iE "required tests (failed|passed)|optional tests failed|BUILD SUCCESSFUL|BUILD FAILED" /tmp/a4a-baseline.log
grep -iE "failed at|step=FAILED" /tmp/a4a-baseline.log | grep -oiE "[a-z]+arena" | tr '[:upper:]' '[:lower:]' | sort -u > /tmp/a4a-ref-fails.txt
cat /tmp/a4a-ref-fails.txt
```
Record the fresh failing-set as the A4a reference. (Note: with §86-89 fixes some previously-failing arenas may now pass — that is expected and NOT related to A4a.)

- [ ] **Step 2: Run the suite on the A4a tip and diff the failing-set.**
```bash
JAVA_TOOL_OPTIONS="-Dworlddriver.mcpPort=39810 -Dworlddriver.rpcPort=39811" \
  ./gradlew :neoforge:runGameTestServer --console=plain 2>&1 | tee /tmp/a4a-gametest.log
grep -iE "required tests (failed|passed)|optional tests failed|BUILD SUCCESSFUL|BUILD FAILED" /tmp/a4a-gametest.log
grep -iE "failed at|step=FAILED" /tmp/a4a-gametest.log | grep -oiE "[a-z]+arena" | tr '[:upper:]' '[:lower:]' | sort -u > /tmp/a4a-fails.txt
diff /tmp/a4a-ref-fails.txt /tmp/a4a-fails.txt && echo "IDENTICAL failing set → A4a is byte-identical (empty bias)" || echo "^ DIFFERS — investigate before A4b"
grep -i "serverProcessArena" /tmp/a4a-gametest.log
```
Expected: IDENTICAL failing set (A4a appends an empty bias → zero cost change → identical planning), and `serverProcessArena` still `reached=true`. Any NEW failure means the plumbing changed behavior — diff the six Walker sites + the `Search` append against this plan; the append must be `costModifiers.addAll(bias)` with `bias` empty at every goto/replay/test call site.

- [ ] **Step 3: CHANGELOG + commit.** Append to `[Unreleased] ### Changed`:
```
- **Internal: the pathfinder now accepts a per-intent cost bias (a `CostModifier`
  list) threaded from the `Intent` through the `Walker` into each search, appended
  after the legacy taxes — inert no-op in A4a (empty bias; byte-identical), the
  channel the LLM navigation intent layer's avoid/prefer/leash biases (A4b) ride.**
```
`git add CHANGELOG.md && git commit -m "changelog: note intent cost-bias plumbing (A4a)"`

---

## Self-Review

**Spec coverage (design §3.1 CostModifier channel + §8 A4):** A4a delivers the plumbing (Intent bias → Walker → PathFinder → Search append). The actual modifiers (`AvoidRegion`/`PreferYBand`/`LeashAnchor`), the verb args, and the detour arena are A4b (follow-on plan). Hard constraints and dynamic anchors are explicitly out (A2 / A3 respectively, per the prior scope decision).

**Placeholder scan:** No TBD. The six Walker sites are located by their `new PathFinder(world` text with a grep-count check (Step 2) rather than fixed line numbers (they shift with the §86-89 rebase). The `Search`-ctor append location is anchored on the A0 `costModifiers.add(...)` seeding block.

**Type consistency:** `Intent.bias()` returns `List<CostModifier>` (Task 1) → `walker.setBias(intent.bias())` (Task 3) → `Walker.bias` field `List<CostModifier>` (Task 3) → `new PathFinder(world, bias)` (Task 3) → `PathFinder(WorldView, List<CostModifier>)` (Task 2) → `costModifiers.addAll(bias)` where `costModifiers` is the A0 `List<CostModifier>`. Consistent end-to-end.
