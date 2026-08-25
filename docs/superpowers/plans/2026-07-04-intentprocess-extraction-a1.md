# IntentProcess Extraction (Phase A1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce an `Intent` value type and a generic `IntentProcess` that supersedes `GotoProcess`, routing the `goto` verb (and replay + server-test paths) through it, with behavior-identical navigation for a static target — the structural seam the LLM navigation intent layer (A2+) builds on.

**Architecture:** `GotoProcess` today is a thin `Goal` + `Walker` driver. Generalize it into `IntentProcess`, which holds an `Intent` (A1: a minimal holder of the target `Goal`) and drives the `Walker` identically. Re-point the three live construction sites and the one server-GameTest site at `IntentProcess`, then delete `GotoProcess`. `kind()` stays `"goto"` so the `BotState.mc_goto` slot mapping and status reporting are unchanged.

**Tech Stack:** Java 21, NeoForge/Fabric multi-loader (`common` holds the pathfinder + processes, `neoforge` holds the GameTests), Gradle, NeoForge GameTest server (`:neoforge:runGameTestServer`).

## Global Constraints

- **`IntentProcess` must be behavior-identical to `GotoProcess` for a static `Goal` target.** This is a parity refactor; navigation behavior may not change. Port the `tick`/`attach`/`onResume`/`onCancelled` logic verbatim.
- **`kind()` must remain `"goto"`** — `UserTaskChain.slotFor("goto")` maps to `BotState.mc_goto`, and status/attach logic keys on it. Changing the kind would strand the goto slot.
- **Avatar-migrated:** `IntentProcess` overrides `tick(Avatar, WorldView, BotState)` (NOT the client `tick(Minecraft,...)`), exactly like `GotoProcess`, so the client bridge and the server FakePlayer path both work.
- **All four `GotoProcess` references move to `IntentProcess`:** `BotApiImpl.java:163`, `BotApiImpl.java:230`, `ReplayInstaller.java:129`, `AgentGameTestServer.java:208`. After rewiring, `GotoProcess.java` is deleted; a dangling reference is a compile failure.
- **SCOPE (documented deviation from design §8):** A1 delivers the `Intent`/`IntentProcess` seam + parity ONLY. The **mutable-goal operation (`amend`) and its verb are deferred to phase B**, where design §5 already places `amend`, so they can be added and tested together (no untested surface). A1's `Intent` is a minimal immutable holder of the target `Goal`.
- **Parity acceptance (per project memory: live/replay is truth; only trust `required tests passed`/`BUILD SUCCESSFUL`; the `TOTAL:` line masks required failures):** the headless GameTest failing set must be UNCHANGED versus the A0 tip. Reference failing set (established by running the suite on the A0 branch and base `e014def`): 4 required — `descentOvershootResyncArena`, `descentYawArena`, `riverSheerBankArena`, `waterFarAimBankCornerArena`; 1 optional — `vineOverWaterClimbArena`. All pre-existing Walker executor arenas, unrelated to this refactor.
- **`serverProcessArena` must STAY GREEN** — it is currently passing and is the server-side proof that the Avatar-migrated process drives a FakePlayer to a goal. After rewiring it to `IntentProcess`, its passing confirms the server Avatar path works through the new process. This is the one arena whose result specifically validates A1.
- **Alternate ports for any test run:** `JAVA_TOOL_OPTIONS="-Dworlddriver.mcpPort=39810 -Dworlddriver.rpcPort=39811"` — another process may be testing on the default ports (39800/39801).

## File Structure

- **Create** `common/src/main/java/net/magicterra/worlddriver/bot/process/Intent.java` — the intent value type. A1 responsibility: hold the target `Goal`. Lives beside `IntentProcess` (they change together); may move to a dedicated `bot/intent/` package when the taxonomy grows in A2+. ~20 lines.
- **Create** `common/src/main/java/net/magicterra/worlddriver/bot/process/IntentProcess.java` — the generic navigation process (ported from `GotoProcess`). One responsibility: drive the `Walker` toward the intent's target. ~55 lines.
- **Modify** `common/src/main/java/net/magicterra/worlddriver/bot/BotApiImpl.java` — two `new GotoProcess(goal)` sites (~163, ~230) → `new IntentProcess(new Intent(goal))`; update the import.
- **Modify** `common/src/main/java/net/magicterra/worlddriver/bot/ReplayInstaller.java` — one site (~129) → `new IntentProcess(new Intent(goal))`; update import + the `{@link GotoProcess}` javadoc reference (~93).
- **Modify** `neoforge/src/main/java/net/magicterra/worlddriver/neoforge/AgentGameTestServer.java` — construction (~208) → `new IntentProcess(new Intent(new Goal.Block(goal)))`; update import + assertion-message strings + the `{@link GotoProcess}` javadoc (~183-186).
- **Delete** `common/src/main/java/net/magicterra/worlddriver/bot/process/GotoProcess.java`.

---

### Task 1: Create the `Intent` value type

**Files:**
- Create: `common/src/main/java/net/magicterra/worlddriver/bot/process/Intent.java`

**Interfaces:**
- Produces: `Intent(Goal target)` constructor and `Goal target()` accessor — consumed by `IntentProcess` (Task 2) and the rewired call sites (Task 3).

- [ ] **Step 1: Create the file**

```java
package net.magicterra.worlddriver.bot.process;

import net.magicterra.worlddriver.bot.Goal;

/**
 * The declarative unit of navigation the {@link IntentProcess} interprets. Phase
 * A1 holds only the target {@link Goal}; later phases of the LLM navigation intent
 * layer grow this with a cost-modifier stack, a capability profile, hard
 * constraints, terminators, and a watch policy (see the design doc). The
 * mutable-goal {@code amend} operation arrives in phase B alongside its verb, so
 * A1 keeps the target immutable here.
 */
public final class Intent {
    private final Goal target;

    public Intent(Goal target) {
        if (target == null) throw new IllegalArgumentException("intent target is null");
        this.target = target;
    }

    /** The A* goal this intent currently converges on. */
    public Goal target() {
        return target;
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :common:compileJava --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add common/src/main/java/net/magicterra/worlddriver/bot/process/Intent.java
git commit -m "process: add Intent value type (A1, target-only)"
```

---

### Task 2: Create `IntentProcess` (ported from `GotoProcess`)

**Files:**
- Create: `common/src/main/java/net/magicterra/worlddriver/bot/process/IntentProcess.java`

**Interfaces:**
- Consumes: `Intent` (Task 1).
- Produces: `IntentProcess(Intent intent)` — consumed by the rewired call sites (Task 3). `kind()` returns `"goto"`.

- [ ] **Step 1: Create the file** (this is `GotoProcess` generalized — the `Walker` driving is byte-identical; the only change is holding an `Intent` and reading `intent.target()` where `GotoProcess` read `goal`)

```java
package net.magicterra.worlddriver.bot.process;

import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.movement.Avatar;
import net.magicterra.worlddriver.bot.movement.Walker;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;

/**
 * The generic navigation process for the LLM navigation intent layer: drives the
 * {@link Walker} toward an {@link Intent}'s target. Supersedes the old
 * {@code GotoProcess} — the {@code mc.bot.goto} verb, replay installs, and the
 * server Avatar proof all build an {@link Intent} and start this process. Phase A1
 * handles a static target (behavior-identical to the old goto); later phases add
 * dynamic/derived targets, cost modifiers, capability profiles, and constraints.
 *
 * <p>{@code kind()} stays {@code "goto"} so the {@link BotState#mc_goto} slot,
 * status reporting, and {@code UserTaskChain} mapping are unchanged.
 */
public final class IntentProcess implements BotProcess {
    private final Intent intent;
    private final Walker walker = new Walker();

    public IntentProcess(Intent intent) {
        this.intent = intent;
        walker.setGoal(intent.target());
    }

    public String kind() { return "goto"; }

    public void attach(BotState st) {
        Goal goal = intent.target();
        st.mc_goto.active = true;
        st.mc_goto.goal = goal.toString();
        if (goal instanceof Goal.Block b) st.mc_goto.target = b.target();
        else if (goal instanceof Goal.Near n) st.mc_goto.target = n.target();
        else if (goal instanceof Goal.TwoBlocks t) st.mc_goto.target = t.target();
        else if (goal instanceof Goal.GetToBlock g) st.mc_goto.target = g.target();
        st.mc_goto.startedAtMs = System.currentTimeMillis();
        st.mc_goto.lastError = null;
    }

    /** Avatar-migrated: drives the client LocalPlayer (via the BotProcess bridge)
     *  or a server FakePlayer (ServerWorldDriver) identically — pure movement, so
     *  it just hands the Walker the same Avatar. */
    @Override public boolean tick(Avatar a, WorldView w, BotState st) {
        Walker.Step s = walker.tick(a, w);
        st.mc_goto.pathLen = walker.pathLen();
        st.mc_goto.pathStep = walker.pathStep();
        if (s == Walker.Step.WALKING) return false;
        if (s == Walker.Step.FAILED) st.mc_goto.lastError = walker.lastError;
        st.mc_goto.reset();
        return true;
    }

    /** Resumed after preemption — discard the stale path and repath from where the
     *  bot ended up (it may have been knocked back while suspended). */
    @Override public void onResume() { walker.forceRepath(); }

    /** Cancelled before arriving (mc.bot.cancel / superseded): fire the Walker's
     *  pathfinder terminal so an in-progress path archive is flushed for the
     *  partial run. */
    @Override public void onCancelled(String reason) { walker.abort(reason); }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :common:compileJava --console=plain`
Expected: `BUILD SUCCESSFUL`. (`GotoProcess` still exists and is still referenced — that's fine; it is deleted in Task 3.)

- [ ] **Step 3: Commit**

```bash
git add common/src/main/java/net/magicterra/worlddriver/bot/process/IntentProcess.java
git commit -m "process: add IntentProcess (A1, generalizes GotoProcess for a static target)"
```

---

### Task 3: Rewire all four sites to `IntentProcess`, delete `GotoProcess`

**Files:**
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/BotApiImpl.java`
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/ReplayInstaller.java`
- Modify: `neoforge/src/main/java/net/magicterra/worlddriver/neoforge/AgentGameTestServer.java`
- Delete: `common/src/main/java/net/magicterra/worlddriver/bot/process/GotoProcess.java`

**Interfaces:**
- Consumes: `Intent`, `IntentProcess` (Tasks 1-2).

- [ ] **Step 1: Rewire `BotApiImpl.java`** — both construction sites. Find `startProcess(new GotoProcess(goal));` (near line 163) and `startProcess(new GotoProcess(g));` (near line 230) and replace with `startProcess(new IntentProcess(new Intent(goal)));` and `startProcess(new IntentProcess(new Intent(g)));` respectively. Update the import: replace `import net.magicterra.worlddriver.bot.process.GotoProcess;` with `import net.magicterra.worlddriver.bot.process.Intent;` and `import net.magicterra.worlddriver.bot.process.IntentProcess;` (add both; keep alphabetical order with the other `process.*` imports).

- [ ] **Step 2: Rewire `ReplayInstaller.java`** — find `bot.startProcess(new GotoProcess(goal));` (near line 129), replace with `bot.startProcess(new IntentProcess(new Intent(goal)));`. Update the import the same way (drop `GotoProcess`, add `Intent` + `IntentProcess`). Update the javadoc `{@link GotoProcess}` (near line 93) to `{@link IntentProcess}`.

- [ ] **Step 3: Rewire `AgentGameTestServer.java`** — find `driver.runProcess(new GotoProcess(new Goal.Block(goal)));` (near line 208), replace with `driver.runProcess(new IntentProcess(new Intent(new Goal.Block(goal))));`. Update the import (drop `GotoProcess`, add `Intent` + `IntentProcess` from `net.magicterra.worlddriver.bot.process`). Update the two assertion-message strings (near 221, 224): `"server GotoProcess did not finish+unregister: ..."` → `"server IntentProcess did not finish+unregister: ..."` and `"server-run GotoProcess did not reach the goal: ..."` → `"server-run IntentProcess did not reach the goal: ..."`. Update the javadoc `{@link GotoProcess}` references (near 183-186) to `{@link IntentProcess}`.

- [ ] **Step 4: Delete `GotoProcess.java`**

```bash
git rm common/src/main/java/net/magicterra/worlddriver/bot/process/GotoProcess.java
```

- [ ] **Step 5: Verify no dangling references remain**

Run: `grep -rn "GotoProcess" common/src neoforge/src fabric/src --include=*.java`
Expected: NO output (zero matches). If any remain, fix them.

- [ ] **Step 6: Compile both modules**

Run: `./gradlew :common:compileJava :neoforge:compileJava --console=plain`
Expected: `BUILD SUCCESSFUL`. A compile failure here means a missed reference or import — fix and recompile.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "process: route goto/replay/server-test through IntentProcess; delete GotoProcess (A1)"
```

---

### Task 4: Parity verification (headless GameTest)

**Files:** none modified except CHANGELOG — verification task.

**Interfaces:** none produced.

- [ ] **Step 1: Run the headless GameTest suite with alternate ports**

Run:
```bash
JAVA_TOOL_OPTIONS="-Dworlddriver.mcpPort=39810 -Dworlddriver.rpcPort=39811" \
  ./gradlew :neoforge:runGameTestServer --console=plain 2>&1 | tee /tmp/a1-gametest.log
```

- [ ] **Step 2: Confirm the failing set is UNCHANGED and `serverProcessArena` is green**

```bash
echo "required/optional:"; grep -iE "required tests (failed|passed)|optional tests failed|BUILD SUCCESSFUL|BUILD FAILED" /tmp/a1-gametest.log
echo "failing arenas:"; grep -iE "failed at|step=FAILED" /tmp/a1-gametest.log | grep -oiE "[a-z]+arena" | tr '[:upper:]' '[:lower:]' | sort -u
echo "serverProcessArena result:"; grep -i "serverProcessArena" /tmp/a1-gametest.log
```
Expected:
- The failing arena set is EXACTLY: `descentovershootresyncarena descentyawarena riversheerbankarena vineoverwaterclimbarena waterfaraimbankcornerarena` (same 4 required + 1 optional as the A0 tip — NO new arena, and NONE of the previously-failing arenas newly passing would be a surprise worth noting but not a regression).
- `serverProcessArena` logs `reached=true` and does NOT appear in the failing set — this proves the server Avatar path drives through `IntentProcess` to the goal.
- Do NOT trust the `TOTAL:` line.

If a NEW arena fails (anything outside the reference set), or `serverProcessArena` fails, A1 changed navigation behavior — investigate before proceeding: diff the `IntentProcess` `tick`/`attach` against the deleted `GotoProcess` for any accidental divergence.

- [ ] **Step 3: CHANGELOG + commit**

Append to `CHANGELOG.md` `[Unreleased]` `### Changed`:
```
- **Internal: `mc.bot.goto` now runs on the generic `IntentProcess` (over an
  `Intent` value type) instead of the bespoke `GotoProcess` — no behavior change
  (A1 groundwork for the LLM navigation intent layer). The old `GotoProcess` is
  removed; `kind()` stays `"goto"` so slots/status are identical; the server
  Avatar proof (`serverProcessArena`) drives the FakePlayer through `IntentProcess`
  unchanged.**
```
Commit:
```bash
git add CHANGELOG.md
git commit -m "changelog: note IntentProcess extraction (A1)"
```

---

## Self-Review

**Spec coverage (design §8 A1 = "Intent value type + IntentProcess (mutable goal, dirty re-solve) with static target + walk capability only. Re-express goto as an adapter. Prove parity with GotoProcess on replay corpus"):**
- "Intent value type" → Task 1. "IntentProcess ... static target" → Task 2. "Re-express goto as an adapter" → Task 3 (the `goto` verb builds an `Intent` → `IntentProcess`; `GotoProcess` removed). "Prove parity" → Task 4 (GameTest failing set unchanged + `serverProcessArena` green).
- **Deviation (documented in Global Constraints):** "mutable goal, dirty re-solve" — the `amend` operation is deferred to phase B (where design §5 places it) to avoid untested surface; A1's `Intent` target is immutable. "walk capability only" is trivially satisfied — A1 adds no capability system; the existing Move set is used exactly as goto uses it today.
- "Prove parity ... on replay corpus" — the live-client pmcs replay corpus is GL-hang-gated (see A0 plan); A1 proves parity via the headless GameTest suite (which drives goto through the arenas) + the `serverProcessArena` Avatar proof. The replay-corpus diff runs when a live client is available.

**Placeholder scan:** No TBD/TODO. All code and commands are concrete. The line numbers (163/230/129/208) are approximate anchors; Task 3 steps instruct locating by the `new GotoProcess(...)` text, and Step 5's grep catches any missed reference.

**Type consistency:** `Intent(Goal)` / `Intent.target()` (Task 1) are used as `new Intent(goal)` and `intent.target()` in Task 2 and `new Intent(goal)`/`new Intent(g)`/`new Intent(new Goal.Block(goal))` in Task 3. `IntentProcess(Intent)` (Task 2) is constructed consistently in Task 3. `kind()=="goto"` matches `UserTaskChain.slotFor` expectations. Consistent.
