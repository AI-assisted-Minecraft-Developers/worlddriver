# Capability Gating + Hard Constraints (Phase A2a) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give an LLM two new per-intent navigation controls over EXISTING moves — a `CapabilityProfile` that forbids move *types* (first citizen: `forbidParkour`) and a `Constraint[]` channel that hard-prunes *edges* (`yFloor`/`yCeil`, `leashHard`) — threaded through the pathfinder like A4a's cost bias and proven by deterministic planner arenas.

**Architecture:** Bundle the three per-intent search inputs (A4a's `bias` + the new `capability` + `constraints`) into one immutable `SearchProfile`, so `Intent`→`Walker`→every `new PathFinder`→`Search` threads ONE value instead of three parallel params. `CapabilityProfile` gates the move CATALOG via a new `Move.requiredCapability()` tag checked in the Search's move-filter loop (generalizing the ad-hoc `suppressPlace && placesBlock()` gate). `Constraint` is a per-edge predicate checked in the A* neighbor loop beside the CostModifier sum. Empty capability/constraints = byte-identical no-op (verified via `serverProcessArena` + causal isolation, per the branch's parity-oracle lesson). New dig-column Moves, tool requirements, and `forbidWater` are DEFERRED to A2b (they need live validation).

**Tech Stack:** Java 21, NeoForge/Fabric multi-loader (`common` = pathfinder + process + resolver + schema; `neoforge` = GameTests), Gradle, `LevelWorldView`, deterministic `PathFinder.findPath`/`Result.path()` assertions.

## Global Constraints

- **Empty profile = byte-identical no-op.** `CapabilityProfile.ALL` (nothing forbidden) must let every move through; empty `constraints` must prune nothing. Adding the capability filter-line and the constraint neighbor-loop check must not alter any planned path when the intent carries neither. (Capability/constraints do NOT touch the FP cost sum, so there is no floating-point associativity concern like A4a — but the planned node sequence must be identical.)
- **Verification oracle = DETERMINISTIC PLANNER arenas** asserting `PathFinder.Result.path()` / `goalReached()`, NEVER executor arenas (project memory: `new Walker()`-driven arenas are run-to-run flaky). Byte-identity of the threading refactor is checked via `serverProcessArena` staying green + causal isolation (the flaky executor arenas drive `new Walker()` directly and are noise).
- **Admissibility unchanged.** Constraints only PRUNE edges (remove successors); they never add negative cost. A* optimality/termination is preserved.
- **YAGNI — A2a scope only.** `Capability` enum carries only `NONE` and `PARKOUR` this phase. Do NOT add `PLACE`/`SWIM`/`DIG`, dig-column moves, `requireTool`, or `forbidWater` — those are A2b. Do NOT fold the Walker's out-of-blocks `suppressPlace` re-plan flag into the capability system (it is a live re-plan path; leave it exactly as-is).
- **Cell-centre convention:** distance-based constraints use the cell centre `(to.x+0.5, to.y, to.z+0.5)` — identical to A4b's `AvoidRegion`/`LeashAnchor`, so `leashHard` and the soft `leash` measure the same geometry.
- Alternate ports for any gametest run: `JAVA_TOOL_OPTIONS="-Dagent.mcpPort=39810 -Dagent.rpcPort=39811"`.

## File Structure

- **Create** `common/.../bot/pathfinder/Capability.java` — enum `{ NONE, PARKOUR }`.
- **Create** `common/.../bot/pathfinder/CapabilityProfile.java` — immutable forbidden-set gate; `ALL` constant; `allows(Capability)`.
- **Create** `common/.../bot/pathfinder/Constraint.java` — `@FunctionalInterface` edge predicate.
- **Create** `common/.../bot/pathfinder/SearchProfile.java` — record `{bias, capability, constraints}` + `NONE`.
- **Create** `common/.../bot/pathfinder/constraints/YFloor.java`, `YCeil.java`, `LeashHardRadius.java`.
- **Modify** `common/.../bot/pathfinder/PathFinder.java` — SearchProfile ctors; Search reads bias/capability/constraints; capability filter-line; constraint neighbor-loop prune.
- **Modify** `common/.../bot/pathfinder/Move.java` — add `requiredCapability()` (default `NONE`).
- **Modify** the 8 `Parkour*` move files — override `requiredCapability()` → `PARKOUR`.
- **Modify** `common/.../bot/process/Intent.java` — add `capability` + `constraints` fields, 4-arg ctor, `searchProfile()`.
- **Modify** `common/.../bot/movement/Walker.java` — `setSearchProfile` replaces `setBias`; 6 search sites use `profile`.
- **Modify** `common/.../bot/process/IntentProcess.java` — `walker.setSearchProfile(intent.searchProfile())`.
- **Modify** `common/.../bot/GotoGoalResolver.java` — `resolveCapability`, `resolveConstraints`.
- **Modify** `common/.../bot/BotApiImpl.java` — 4-arg `new Intent(...)` at the goto site.
- **Modify** `common/.../mcp/catalog/BotTools.java` — schema/help for `forbidParkour`/`capability`/`yFloor`/`yCeil`/`leashHard`.
- **Modify** `neoforge/.../AgentGameTestBias.java` — add 3 deterministic planner arenas.

---

### Task 1: New value types (Capability, CapabilityProfile, Constraint, SearchProfile)

**Files:** Create the four types below. Pure value types, no wiring.

**Interfaces:**
- Produces: `Capability` (enum), `CapabilityProfile` (`ALL`, `allows(Capability)`, `isEmpty()`), `Constraint` (`allows(from,to,edge,goal,world)`), `SearchProfile` (`bias()`, `capability()`, `constraints()`, `NONE`).

- [ ] **Step 1: `Capability.java`**
```java
package net.magicterra.agent.bot.pathfinder;

/** A move-type category that a {@link CapabilityProfile} can forbid per-intent.
 *  {@code NONE} = ungated (the vast majority of moves). A2a wires only PARKOUR;
 *  A2b will add PLACE/SWIM/DIG. */
public enum Capability { NONE, PARKOUR }
```

- [ ] **Step 2: `CapabilityProfile.java`**
```java
package net.magicterra.agent.bot.pathfinder;

import java.util.EnumSet;
import java.util.Set;

/** Per-intent gate on which move TYPES a search may use. Immutable. The default
 *  {@link #ALL} (empty forbidden set) allows every move → byte-identical no-op. */
public final class CapabilityProfile {
    /** Allows every capability — the no-op default carried by a plain intent. */
    public static final CapabilityProfile ALL = new CapabilityProfile(EnumSet.noneOf(Capability.class));

    private final Set<Capability> forbidden;

    public CapabilityProfile(Set<Capability> forbidden) {
        this.forbidden = (forbidden == null || forbidden.isEmpty())
                ? EnumSet.noneOf(Capability.class) : EnumSet.copyOf(forbidden);
    }

    /** True unless this move's required capability is forbidden. {@code NONE} is never forbidden. */
    public boolean allows(Capability c) { return c == Capability.NONE || !forbidden.contains(c); }

    public boolean isEmpty() { return forbidden.isEmpty(); }
}
```

- [ ] **Step 3: `Constraint.java`**
```java
package net.magicterra.agent.bot.pathfinder;

import net.magicterra.agent.bot.Goal;
import net.minecraft.core.BlockPos;

/** A hard, per-intent edge predicate: return {@code false} to PRUNE the move to
 *  {@code to} (the successor is never generated). Unlike {@link CostModifier}
 *  (soft, additive cost) a Constraint removes the edge entirely. Checked in the
 *  A* neighbor loop. Must be a pure function of its args (no side effects). */
@FunctionalInterface
public interface Constraint {
    boolean allows(BlockPos from, BlockPos to, Move.Edge edge, Goal goal, WorldView world);
}
```

- [ ] **Step 4: `SearchProfile.java`**
```java
package net.magicterra.agent.bot.pathfinder;

import java.util.List;

/** The per-intent inputs to a search, bundled so {@link PathFinder}/Walker thread
 *  ONE value instead of three parallel params. {@link #NONE} = plain search,
 *  byte-identical to pre-A2a. All fields defensively copied / defaulted. */
public record SearchProfile(List<CostModifier> bias, CapabilityProfile capability, List<Constraint> constraints) {
    public static final SearchProfile NONE =
            new SearchProfile(List.of(), CapabilityProfile.ALL, List.of());

    public SearchProfile {
        bias = (bias == null) ? List.of() : List.copyOf(bias);
        capability = (capability == null) ? CapabilityProfile.ALL : capability;
        constraints = (constraints == null) ? List.of() : List.copyOf(constraints);
    }
}
```

- [ ] **Step 5: Compile** — `./gradlew :common:compileJava --console=plain` → `BUILD SUCCESSFUL`.
- [ ] **Step 6: Commit** — `git add common/src/main/java/net/magicterra/agent/bot/pathfinder/{Capability,CapabilityProfile,Constraint,SearchProfile}.java && git commit -m "pathfinder: Capability/CapabilityProfile/Constraint/SearchProfile value types (A2a Task 1)"`

---

### Task 2: PathFinder — accept SearchProfile; gate moves by capability; prune edges by constraint

**Files:** Modify `common/.../bot/pathfinder/PathFinder.java`

**Interfaces:**
- Consumes: `SearchProfile`, `CapabilityProfile`, `Constraint`, `Capability` (Task 1); `Move.requiredCapability()` (Task 4 — until Task 4 lands, every move returns the base default `NONE`, so the capability gate is inert; that is fine and keeps this task independently testable).
- Produces: `PathFinder(WorldView, SearchProfile)` and `PathFinder(WorldView, int, long, SearchProfile)` ctors.

**Context:** Post-A4a the field is `private final List<CostModifier> bias;` (line ~99) with ctors at lines 103-117; the Search ctor seeds `costModifiers` then `costModifiers.addAll(bias)` at line ~249; the move-filter loop is at ~221-227; the neighbor loop computes `BlockPos npos = edge.to;` then `ng` then sums `costModifiers` (~722-729). Read those regions first.

- [ ] **Step 1: Replace the `bias` field with `profile`.** Change `private final List<CostModifier> bias;` to:
```java
    private final SearchProfile profile;
```

- [ ] **Step 2: Rework the four ctors** (lines ~103-117) to funnel through a SearchProfile. KEEP the two `List<CostModifier> bias` ctors as thin back-compat delegators (Walker still calls them until Task 3), so this task compiles and stays byte-identical:
```java
    public PathFinder(WorldView world) {
        this(world, BotConfig.pathfinderMaxNodes, BotConfig.pathfinderMaxMs, SearchProfile.NONE);
    }
    public PathFinder(WorldView world, SearchProfile profile) {
        this(world, BotConfig.pathfinderMaxNodes, BotConfig.pathfinderMaxMs, profile);
    }
    /** Back-compat: bias-only search (kept until Walker migrates to SearchProfile in A2a Task 3). */
    public PathFinder(WorldView world, java.util.List<CostModifier> bias) {
        this(world, BotConfig.pathfinderMaxNodes, BotConfig.pathfinderMaxMs,
                new SearchProfile(bias, CapabilityProfile.ALL, java.util.List.of()));
    }
    public PathFinder(WorldView world, int maxNodes, long maxMs) {
        this(world, maxNodes, maxMs, SearchProfile.NONE);
    }
    /** Back-compat bias-only + budget (kept until Task 3). */
    public PathFinder(WorldView world, int maxNodes, long maxMs, java.util.List<CostModifier> bias) {
        this(world, maxNodes, maxMs, new SearchProfile(bias, CapabilityProfile.ALL, java.util.List.of()));
    }
    public PathFinder(WorldView world, int maxNodes, long maxMs, SearchProfile profile) {
        this.world = world;
        this.maxNodes = maxNodes;
        this.maxMs = maxMs;
        this.profile = (profile == null) ? SearchProfile.NONE : profile;
    }
```

- [ ] **Step 3: Add two Search fields** (near the `costModifiers` field ~207):
```java
        private final CapabilityProfile capability;
        private final List<Constraint> constraints;
```

- [ ] **Step 4: Set them + keep the bias source, in the Search ctor.** Change the A4a line `costModifiers.addAll(bias);` (~249) to read from the profile, and set the two new fields (do this near the top of the Search ctor, after `this.goal = goal;` so they're available; the `costModifiers.addAll` stays where it is):
```java
        // in the Search ctor, alongside the other field assignments:
        this.capability = PathFinder.this.profile.capability();
        this.constraints = PathFinder.this.profile.constraints();
```
and change the append line to:
```java
            costModifiers.addAll(PathFinder.this.profile.bias());
```
(Same list contents/order as A4a when the profile carries the same bias → byte-identical FP sum.)

- [ ] **Step 5: Add the capability filter-line** in the move-filter loop (~221-227), AFTER the existing `suppressPlace` line, BEFORE `active.add(m)`:
```java
                if (!capability.allows(m.requiredCapability())) continue;   // A2a: per-intent move-type gate
```
(With `CapabilityProfile.ALL` this never skips → no-op. Note: `capability` field must be set before this loop runs — Step 4 sets it earlier in the ctor; verify ordering, move the assignment up if the filter loop precedes it.)

- [ ] **Step 6: Add the constraint prune** in the neighbor loop, immediately AFTER `BlockPos npos = edge.to;`:
```java
                        if (!constraints.isEmpty()) {
                            boolean pruned = false;
                            for (Constraint c : constraints) {
                                if (!c.allows(cur.pos, npos, edge, goal, world)) { pruned = true; break; }
                            }
                            if (pruned) continue;   // A2a: hard edge prune (successor never generated)
                        }
```
(Empty constraints → the whole block is skipped → no-op.)

- [ ] **Step 7: Compile** — `./gradlew :common:compileJava --console=plain` → `BUILD SUCCESSFUL`.
- [ ] **Step 8: Commit** — `git add common/src/main/java/net/magicterra/agent/bot/pathfinder/PathFinder.java && git commit -m "pathfinder: Search takes SearchProfile; capability move-gate + constraint edge-prune (A2a Task 2, empty=no-op)"`

---

### Task 3: Thread SearchProfile through Intent → Walker → IntentProcess; retire the bias-only path

**Files:** Modify `common/.../bot/process/Intent.java`, `common/.../bot/movement/Walker.java`, `common/.../bot/process/IntentProcess.java`, `common/.../bot/pathfinder/PathFinder.java`

**Interfaces:**
- Consumes: `SearchProfile`, `CapabilityProfile`, `Constraint` (Task 1); `PathFinder(WorldView, SearchProfile)` + `PathFinder(WorldView, int, long, SearchProfile)` (Task 2).
- Produces: `Intent(Goal, List<CostModifier>, CapabilityProfile, List<Constraint>)` ctor + `Intent.capability()`, `Intent.constraints()`, `Intent.searchProfile()`; `Walker.setSearchProfile(SearchProfile)`.

- [ ] **Step 1: `Intent.java` — add the two fields + 4-arg ctor + accessors + `searchProfile()`.** Keep the existing 1-arg and 2-arg ctors (they now default capability/constraints). Add imports for `CapabilityProfile`, `Constraint`, `SearchProfile`. Result:
```java
    private final Goal target;
    private final List<CostModifier> bias;
    private final CapabilityProfile capability;
    private final List<Constraint> constraints;

    public Intent(Goal target) { this(target, List.of(), CapabilityProfile.ALL, List.of()); }
    public Intent(Goal target, List<CostModifier> bias) { this(target, bias, CapabilityProfile.ALL, List.of()); }
    public Intent(Goal target, List<CostModifier> bias, CapabilityProfile capability, List<Constraint> constraints) {
        if (target == null) throw new IllegalArgumentException("intent target is null");
        this.target = target;
        this.bias = (bias == null) ? List.of() : List.copyOf(bias);
        this.capability = (capability == null) ? CapabilityProfile.ALL : capability;
        this.constraints = (constraints == null) ? List.of() : List.copyOf(constraints);
    }

    public Goal target() { return target; }
    public List<CostModifier> bias() { return bias; }
    public CapabilityProfile capability() { return capability; }
    public List<Constraint> constraints() { return constraints; }
    public SearchProfile searchProfile() { return new SearchProfile(bias, capability, constraints); }
```

- [ ] **Step 2: `Walker.java` — replace `bias` field + `setBias` with `profile` + `setSearchProfile`.** Change line ~58 `private java.util.List<CostModifier> bias = java.util.List.of();` and the `setBias` method (~61) to:
```java
    private net.magicterra.agent.bot.pathfinder.SearchProfile profile =
            net.magicterra.agent.bot.pathfinder.SearchProfile.NONE;

    public void setSearchProfile(net.magicterra.agent.bot.pathfinder.SearchProfile p) {
        this.profile = (p == null) ? net.magicterra.agent.bot.pathfinder.SearchProfile.NONE : p;
    }
```
(Remove the now-unused `import ...CostModifier;` at line 7 only if nothing else in Walker uses it — grep first; leave it if used elsewhere.)

- [ ] **Step 3: `Walker.java` — migrate the 6 search sites** from `bias` to `profile`. Lines 1157, 1165, 1317, 2064, 4895 are `new PathFinder(world, bias).newSearch(...)` → change `bias` to `profile`. Line 4940 is `new PathFinder(world, BotConfig.pathfinderQuickNodes, QUICK_MAX_MS, bias)` → change `bias` to `profile`. (The `new PathFinder.Result(...)` calls at 407/424/441/5001/5049 are NOT searches — leave them.) Confirm with `grep -n "new PathFinder(world" Walker.java` that exactly these move.

- [ ] **Step 4: `IntentProcess.java` — set the profile.** Change line ~27 `walker.setBias(intent.bias());` to:
```java
        walker.setSearchProfile(intent.searchProfile());
```

- [ ] **Step 5: `PathFinder.java` — remove the two back-compat bias ctors** added in Task 2 Step 2 (the `PathFinder(WorldView, List<CostModifier>)` and `PathFinder(WorldView, int, long, List<CostModifier>)` delegators), now that no caller uses them. Confirm zero callers first: `grep -rn "new PathFinder(.*bias\|new PathFinder([^,]*, *java.util.List\|new PathFinder([^,]*, *List<" common neoforge`. Keep the `SearchProfile` ctors.

- [ ] **Step 6: Compile** — `./gradlew :common:compileJava --console=plain` → `BUILD SUCCESSFUL`.
- [ ] **Step 7: Byte-identity check — run `serverProcessArena` (the process-path parity gate).**
```bash
JAVA_TOOL_OPTIONS="-Dagent.mcpPort=39810 -Dagent.rpcPort=39811" \
  ./gradlew :neoforge:runGameTestServer --console=plain 2>&1 | tee /tmp/a2a-t3.log
grep -iE "serverProcessArena|BUILD (SUCCESSFUL|FAILED)" /tmp/a2a-t3.log
```
Expected: `[serverProcessArena] step=ARRIVED ... reached=true`. (Overall BUILD may be FAILED from the known flaky executor arenas — that is noise; the signal is serverProcessArena ARRIVED. The threading is byte-identical because every intent here carries `SearchProfile.NONE` → empty bias/ALL-capability/empty-constraints → the Search behaves exactly as pre-A2a.)
- [ ] **Step 8: Commit** — `git add -A && git commit -m "process/movement: thread SearchProfile Intent→Walker→IntentProcess; retire bias-only path (A2a Task 3)"`

---

### Task 4: `Move.requiredCapability()` + Parkour overrides

**Files:** Modify `common/.../bot/pathfinder/Move.java` and the 8 `Parkour*` move files.

**Interfaces:**
- Produces: `Move.requiredCapability()` returning `Capability` (default `NONE`); `Parkour*` moves return `PARKOUR`.

- [ ] **Step 1: Add the base method to `Move.java`** (near `placesBlock()` ~line 64; add `import net.magicterra.agent.bot.pathfinder.Capability;` only if `Move` is in a different package — it is in `pathfinder`, so no import needed):
```java
    /** The capability a {@link CapabilityProfile} must permit for this move to be
     *  usable in a search. Default {@code NONE} (ungated). Overridden by the move
     *  categories a per-intent profile can forbid (A2a: Parkour → PARKOUR). */
    public Capability requiredCapability() { return Capability.NONE; }
```

- [ ] **Step 2: Override in each Parkour move.** In EACH of these 8 files add the override method (inside the class body):
`moves/Parkour2.java`, `moves/Parkour3.java`, `moves/Parkour4.java`, `moves/Parkour2Diagonal.java`, `moves/Parkour3Diagonal.java`, `moves/ParkourAscend.java`, `moves/ParkourDescend.java`, `moves/ParkourPlace.java`:
```java
    @Override public net.magicterra.agent.bot.pathfinder.Capability requiredCapability() {
        return net.magicterra.agent.bot.pathfinder.Capability.PARKOUR;
    }
```
(These files are in package `...pathfinder.moves`, so the FQN or an import of `Capability` is needed. Use the import `import net.magicterra.agent.bot.pathfinder.Capability;` and write `return Capability.PARKOUR;` if you prefer — match the file's existing import style; `ParkourPlace` also `placesBlock()` returns true, which is fine — it stays gated by PLACE logic separately in A2b, but for A2a it just carries PARKOUR.)
- [ ] **Step 3: Verify you covered every Parkour move:** `grep -Ln "requiredCapability" common/src/main/java/net/magicterra/agent/bot/pathfinder/moves/Parkour*.java` must print nothing (every `Parkour*.java` now has the override).
- [ ] **Step 4: Compile** — `./gradlew :common:compileJava --console=plain` → `BUILD SUCCESSFUL`.
- [ ] **Step 5: Commit** — `git add common/src/main/java/net/magicterra/agent/bot/pathfinder/Move.java common/src/main/java/net/magicterra/agent/bot/pathfinder/moves/Parkour*.java && git commit -m "pathfinder: Move.requiredCapability() tag; Parkour* → PARKOUR (A2a Task 4)"`

---

### Task 5: Constraint implementations (YFloor, YCeil, LeashHardRadius)

**Files:** Create `common/.../bot/pathfinder/constraints/{YFloor,YCeil,LeashHardRadius}.java`

**Interfaces:**
- Consumes: `Constraint` (Task 1).
- Produces: `record YFloor(int minY)`, `record YCeil(int maxY)`, `record LeashHardRadius(double ax, double ay, double az, double radius)` — all `implements Constraint`.

- [ ] **Step 1: `YFloor.java`**
```java
package net.magicterra.agent.bot.pathfinder.constraints;

import net.magicterra.agent.bot.Goal;
import net.magicterra.agent.bot.pathfinder.Constraint;
import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

/** Hard floor: prune any move whose destination is below {@code minY}. Backs
 *  "don't go below Y=N" (e.g. stay out of the caves). */
public record YFloor(int minY) implements Constraint {
    @Override
    public boolean allows(BlockPos from, BlockPos to, Move.Edge edge, Goal goal, WorldView world) {
        return to.getY() >= minY;
    }
}
```

- [ ] **Step 2: `YCeil.java`**
```java
package net.magicterra.agent.bot.pathfinder.constraints;

import net.magicterra.agent.bot.Goal;
import net.magicterra.agent.bot.pathfinder.Constraint;
import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

/** Hard ceiling: prune any move whose destination is above {@code maxY}. */
public record YCeil(int maxY) implements Constraint {
    @Override
    public boolean allows(BlockPos from, BlockPos to, Move.Edge edge, Goal goal, WorldView world) {
        return to.getY() <= maxY;
    }
}
```

- [ ] **Step 3: `LeashHardRadius.java`** (cell-centre convention, matching A4b `LeashAnchor`)
```java
package net.magicterra.agent.bot.pathfinder.constraints;

import net.magicterra.agent.bot.Goal;
import net.magicterra.agent.bot.pathfinder.Constraint;
import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

/** Hard leash: prune any move whose destination is farther than {@code radius}
 *  from the anchor. The hard twin of A4b's soft {@code LeashAnchor} — the route
 *  MAY NOT leave the radius at all (vs paying a cost to). Backs "带路别离太远"
 *  as a firm bound. */
public record LeashHardRadius(double ax, double ay, double az, double radius) implements Constraint {
    @Override
    public boolean allows(BlockPos from, BlockPos to, Move.Edge edge, Goal goal, WorldView world) {
        if (radius <= 0) return true;   // unset → no leash
        double dx = (to.getX() + 0.5) - ax, dy = to.getY() - ay, dz = (to.getZ() + 0.5) - az;
        return Math.sqrt(dx * dx + dy * dy + dz * dz) <= radius;
    }
}
```

- [ ] **Step 4: Compile** — `./gradlew :common:compileJava --console=plain` → `BUILD SUCCESSFUL`.
- [ ] **Step 5: Commit** — `git add common/src/main/java/net/magicterra/agent/bot/pathfinder/constraints/ && git commit -m "pathfinder/constraints: YFloor + YCeil + LeashHardRadius (A2a Task 5)"`

---

### Task 6: Parse the verb args + schema

**Files:** Modify `common/.../bot/GotoGoalResolver.java`, `common/.../bot/BotApiImpl.java`, `common/.../mcp/catalog/BotTools.java`

**Interfaces:**
- Consumes: `CapabilityProfile`, `Capability`, `Constraint` (Task 1); `YFloor`, `YCeil`, `LeashHardRadius` (Task 5); `Intent(Goal, List<CostModifier>, CapabilityProfile, List<Constraint>)` (Task 3); existing `GotoGoalResolver.resolveBias` (A4b).
- Produces: `GotoGoalResolver.resolveCapability(Params)`, `GotoGoalResolver.resolveConstraints(Params)`.

**Context:** Read `resolveBias` (A4b, ~line 127) for the exact `Params` accessor idiom (`p.get(...) instanceof ...`, `p.getBool(...)`, `Params.toDouble(...)`). Mirror it.

- [ ] **Step 1: Add `resolveCapability` to `GotoGoalResolver`.**
```java
    /** forbidParkour:true OR capability:"walk" → forbid PARKOUR; otherwise ALL (no-op). */
    static net.magicterra.agent.bot.pathfinder.CapabilityProfile resolveCapability(Params p) {
        boolean forbidParkour = p.getBool("forbidParkour")
                || (p.get("capability") instanceof String s && s.trim().equalsIgnoreCase("walk"));
        if (!forbidParkour) return net.magicterra.agent.bot.pathfinder.CapabilityProfile.ALL;
        return new net.magicterra.agent.bot.pathfinder.CapabilityProfile(
                java.util.EnumSet.of(net.magicterra.agent.bot.pathfinder.Capability.PARKOUR));
    }
```

- [ ] **Step 2: Add `resolveConstraints` to `GotoGoalResolver`.**
```java
    /** yFloor / yCeil / leashHard → hard Constraints. Empty when none supplied. */
    static java.util.List<net.magicterra.agent.bot.pathfinder.Constraint> resolveConstraints(Params p) {
        java.util.List<net.magicterra.agent.bot.pathfinder.Constraint> cs = new java.util.ArrayList<>();
        if (p.get("yFloor") instanceof Number n)
            cs.add(new net.magicterra.agent.bot.pathfinder.constraints.YFloor((int) Math.floor(n.doubleValue())));
        if (p.get("yCeil") instanceof Number n)
            cs.add(new net.magicterra.agent.bot.pathfinder.constraints.YCeil((int) Math.floor(n.doubleValue())));
        if (p.get("leashHard") instanceof java.util.Map<?, ?> l) {
            Object x = l.get("x"), y = l.get("y"), z = l.get("z"), r = l.get("radius");
            if (x instanceof Number && y instanceof Number && z instanceof Number && r instanceof Number) {
                cs.add(new net.magicterra.agent.bot.pathfinder.constraints.LeashHardRadius(
                        ((Number) x).doubleValue(), ((Number) y).doubleValue(),
                        ((Number) z).doubleValue(), ((Number) r).doubleValue()));
            }
        }
        return cs;
    }
```
(If `Params` has no `getBool`, use the idiom `p.get("forbidParkour") instanceof Boolean b && b` — verify against the file. Reuse `Params.toDouble` if you prefer over the `instanceof Number` casts, matching `resolveBias`.)

- [ ] **Step 3: Wire `BotApiImpl`.** At the goto site (the A4b line `new Intent(goal, GotoGoalResolver.resolveBias(p))`), change to:
```java
        startProcess(new IntentProcess(new Intent(goal,
                GotoGoalResolver.resolveBias(p),
                GotoGoalResolver.resolveCapability(p),
                GotoGoalResolver.resolveConstraints(p))));
```
(Match the actual surrounding call shape — find it with `grep -n "resolveBias" common/src/main/java/net/magicterra/agent/bot/BotApiImpl.java`.)

- [ ] **Step 4: Schema/help in `BotTools.java`.** In the goto tool block (after the A4b `avoid`/`preferY`/`leash` props ~line 84-91), add — matching the file's `object()`/`number()`/`bool()`/`string()` builder helpers (read the block to confirm the exact helper for a boolean; if `bool()` doesn't exist use whatever the file uses for boolean props):
```java
                    .prop("forbidParkour", bool())
                    .prop("capability", string())
                    .prop("yFloor", number())
                    .prop("yCeil", number())
                    .prop("leashHard", object()
                            .prop("x", number()).prop("y", number()).prop("z", number())
                            .prop("radius", number()))
```
and add help lines matching the existing goto help tone:
```
  forbidParkour  true → drop all parkour moves (also: capability:"walk"). Route must not jump gaps.
  yFloor / yCeil  N — hard-limit the route's Y (prune cells below yFloor / above yCeil). E.g. keep out of caves.
  leashHard  {x,y,z,radius} — HARD tether: route may not leave the radius at all (firm twin of soft `leash`).
```

- [ ] **Step 5: Compile** — `./gradlew :common:compileJava --console=plain` → `BUILD SUCCESSFUL`.
- [ ] **Step 6: Commit** — `git add -A && git commit -m "goto: parse forbidParkour/yFloor/yCeil/leashHard into the Intent; schema (A2a Task 6)"`

---

### Task 7: Deterministic planner arenas + CHANGELOG

**Files:** Modify `neoforge/.../AgentGameTestBias.java` (add 3 methods)

**Interfaces:** Consumes `CapabilityProfile`, `Capability`, `SearchProfile`, `Constraint`, `YFloor`, `LeashHardRadius`, `PathFinder(WorldView, SearchProfile)`, `Goal`, `LevelWorldView`.

**Context:** Mirror the existing `avoidRegionDetourArena` in this file (`@GameTest(template="empty", timeoutTicks=100000)`, `ServerPlayerAvatar.create(...).fakePlayer()` → `new LevelWorldView(level, fp)`, `new PathFinder(w, profile).findPath(start, new Goal.Block(goal))`, assert on `Result.path()` / `Result.goalReached()`). All three assert on the deterministic planned path — NO executor. Build geometry at high Y clear of terrain, coords far from other arenas.

- [ ] **Step 1: `parkourGateArena`.** Build two platforms separated by a gap that ONLY a parkour move can cross (no walkable floor between; gap width = 2 so a `Parkour2`/`ParkourAscend` is the sole crossing). Plan PLAIN (`SearchProfile.NONE`) start→goal on the far platform: assert `goalReached==true` (parkour crosses). Plan with a walk-only profile `new SearchProfile(List.of(), new CapabilityProfile(EnumSet.of(Capability.PARKOUR)), List.of())`: assert `goalReached==false` (no non-parkour crossing exists → unreachable). Log both `goalReached`. This proves the capability gate removes parkour moves. If the plain plan does NOT cross (geometry lets it walk around), tighten the gap/remove any floor so parkour is the only option — that tuning is part of the task.
- [ ] **Step 2: `yFloorConstraintArena`.** Build a goal reachable two ways: a SHORT path that dips to a low Y row, and a longer path that stays high. Plan PLAIN: assert the path's min-Y reaches the low dip (`minPathY(plain) <= dipY`). Plan with `constraints=[new YFloor(dipY+1)]`: assert `goalReached==true` AND the path's min-Y stays `>= dipY+1` (the low dip is pruned → it took the high route). Use the `maxPathY`/`minPathY` helper pattern (`AgentGameTestSupport.maxPathY` exists; write a local `minPathY` mirroring it). This proves the constraint prunes edges by `to.y`.
- [ ] **Step 3: `leashHardArena`.** Flat lane; anchor at the start. Plan with `constraints=[new LeashHardRadius(startX+0.5, startY, startZ+0.5, R)]` to a goal INSIDE radius R: assert `goalReached==true`. Plan the SAME leash to a goal OUTSIDE radius R (farther than R along the lane): assert `goalReached==false` (every edge past R is pruned → unreachable). This proves the hard radius bound.
- [ ] **Step 4: Run the three arenas headless.**
```bash
JAVA_TOOL_OPTIONS="-Dagent.mcpPort=39810 -Dagent.rpcPort=39811" \
  ./gradlew :neoforge:runGameTestServer --console=plain 2>&1 | tee /tmp/a2a-t7.log
grep -iE "parkourGate|yFloorConstraint|leashHard|required tests (failed|passed)" /tmp/a2a-t7.log
```
Expected: all three log their pass values and NONE appears in a `failed at` line. (Pre-existing flaky executor arenas may still fail — noise; judge only your three new arenas + that they aren't in the failure set.) Tune geometry until each holds deterministically.
- [ ] **Step 5: CHANGELOG + commit.** Append to `[Unreleased] ### Added`:
```
- **`mc.bot.goto` gains hard navigation controls via the intent layer: `forbidParkour`
  (drop parkour moves), `yFloor`/`yCeil` (hard-limit route Y), and `leashHard`
  (firm radius tether — the hard twin of the soft `leash`). Enforced by a per-intent
  `CapabilityProfile` (move-type gate) and `Constraint` edge-prune in the pathfinder.**
```
`git add -A && git commit -m "gametest: deterministic parkour-gate/yFloor/leashHard arenas + changelog (A2a Task 7)"`

---

## Self-Review

**Spec coverage (A2a section of the design spec):** CapabilityProfile move-gate via `Move.requiredCapability()` (Tasks 1,2,4) ✓; Constraint edge-prune channel (Tasks 1,2,5) ✓; threaded like A4a bias, bundled as SearchProfile (Tasks 2,3) ✓; three citizens forbidParkour/yFloor+yCeil/leashHard (Tasks 4,5,6) ✓; verb args + schema (Task 6) ✓; deterministic planner arenas (Task 7) ✓. Deferred correctly to A2b: dig-column moves, requireTool, forbidWater, PLACE/SWIM/DIG capabilities (Global Constraints). Byte-identity gate = serverProcessArena + causal isolation (Task 3 Step 7) ✓.

**Placeholder scan:** Task 6 flags the two soft spots (Params boolean idiom; BotTools boolean-prop helper) with explicit "verify against the file" fallbacks rather than guessing. Task 7 geometry is described with the tuning contract (like A4b Task 6) since exact block coords depend on move-eval behavior best confirmed at run time. Tasks 1-5 are concrete verbatim code.

**Type consistency:** `SearchProfile(List<CostModifier> bias, CapabilityProfile capability, List<Constraint> constraints)` is consumed identically in PathFinder (Task 2), Intent.searchProfile() (Task 3), and the arenas (Task 7). `CapabilityProfile.ALL` / `allows(Capability)` and `Constraint.allows(from,to,edge,goal,world)` signatures match across Tasks 1,2,4,5,6,7. `Move.requiredCapability()→Capability` (Task 4) is what Task 2's filter-line calls. Intent's 1-/2-/4-arg ctors keep A1/A4b callers (`serverProcessArena`'s `new Intent(new Goal.Block(...))`, BotApiImpl's A4b 2-arg → upgraded to 4-arg in Task 6) compiling throughout.
