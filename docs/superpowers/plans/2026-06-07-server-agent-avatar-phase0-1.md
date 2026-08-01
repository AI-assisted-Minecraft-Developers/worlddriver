# Server-Side Agent `Avatar` — Phase 0+1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans (inline) to implement task-by-task. Steps use `- [ ]` checkboxes.

**Goal:** Decouple the Walker from `LocalPlayer` behind an `Avatar` seam (ClientPlayerAvatar 1:1 passthrough + ServerPlayerAvatar over a FakePlayer) and prove it headlessly via a physics-parity gate + a canopy-pillar arena GameTest — zero client regression.

**Architecture:** Introduce `Avatar` (actuation+self-state interface, common) with `ClientPlayerAvatar` (common, passthrough) and `ServerPlayerAvatar` (neoforge, FakePlayer). Add live mutable `LevelWorldView`. Run the real `Walker` over both. Validate with two new neoforge `@GameTest`s.

**Tech Stack:** Java 21, NeoForge/Architectury MC 1.21.1, vanilla `LivingEntity.travel/move`, NeoForge `FakePlayerFactory`, GameTest.

---

## File Structure

- Create `common/.../bot/movement/Avatar.java` — interface (actuation + reads + capabilities).
- Create `common/.../bot/movement/BodyCapabilities.java` — capability flags record.
- Create `common/.../bot/movement/ClientPlayerAvatar.java` — passthrough impl wrapping `Minecraft`.
- Create `common/.../bot/world/LevelWorldView.java` — live mutable `WorldView` over `ServerLevel` (break/place + entity-inventory breakCost).
- Create `neoforge/.../neoforge/sim/ServerPlayerAvatar.java` — FakePlayer-backed Avatar + manual physics step.
- Modify `common/.../bot/movement/Walker.java` — `tick(Avatar, WorldView)`; migrate ~87 client sites to `avatar.*`.
- Modify `common/.../bot/BotApiImpl.java` (clientTick) — construct `ClientPlayerAvatar(mc)`, call `Walker.tick(avatar, world)`.
- Create `neoforge/.../neoforge/SimPhysicsParityTest.java` (or add to AgentGameTest) — `@GameTest` physics gate.
- Create `neoforge/.../neoforge/SummitArenaTest.java` (or add to AgentGameTest) — `@GameTest` canopy reach.

---

## Task 1: `BodyCapabilities`

**Files:** Create `common/src/main/java/net/magicterra/worlddriver/bot/movement/BodyCapabilities.java`

- [ ] **Step 1: Implement** — immutable record of flags + movement caps.

```java
package net.magicterra.worlddriver.bot.movement;

/** What an Avatar's underlying entity can do, so the orchestrator skips
 *  Player-only chains and the planner reads entity movement caps. */
public record BodyCapabilities(
        boolean canPlace, boolean canBreak, boolean canCraft, boolean canSprint,
        int stepUpBlocks, int jumpUpBlocks, double aabbWidth) {
    public static final BodyCapabilities PLAYER =
            new BodyCapabilities(true, true, true, true, 0, 1, 0.6);
}
```

- [ ] **Step 2: Compile** — `./gradlew :common:compileJava -q` → EXIT 0.
- [ ] **Step 3: Commit** — `git add` the file; `git commit -m "feat(avatar): BodyCapabilities flags"`.

---

## Task 2: `Avatar` interface

**Files:** Create `common/src/main/java/net/magicterra/worlddriver/bot/movement/Avatar.java`

- [ ] **Step 1: Define interface** — exactly the surface the Walker uses (reads + movement + block writes). Use vanilla types already imported by Walker.

```java
package net.magicterra.worlddriver.bot.movement;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.ItemStack;

/** Actuation + self-state surface over the entity the agent drives.
 *  ClientPlayerAvatar = current LocalPlayer behaviour (1:1); ServerPlayerAvatar = FakePlayer. */
public interface Avatar {
    // reads
    double x(); double y(); double z();
    Vec3 deltaMovement();
    float yRot(); float xRot();
    boolean onGround();
    boolean inWater(); boolean underWater();
    AABB boundingBox();
    Vec3 eyePos();
    ItemStack heldItem();
    boolean flying();
    BlockPos foot();             // floored feet BlockPos (convenience)

    // movement writes (camera-decoupled: commandMove pre-rotated by aimYaw-yRot)
    void commandMove(float left, float forward);
    void commandJump(boolean v);
    void commandSneak(boolean v);
    void setSprinting(boolean v);
    void setYaw(float yaw);
    void setPitch(float pitch);
    void requestLookSnap();

    // block writes
    boolean holdPlaceable();                 // ensure a solid-support BlockItem in hand
    void placeSupport(BlockPos cell, Direction face);
    void selectToolFor(BlockPos cell);
    void breakHold(boolean v);               // hold/release the break action
    void aimAtBlock(BlockPos cell);

    BodyCapabilities capabilities();
}
```

- [ ] **Step 2: Compile** `:common:compileJava` → EXIT 0.
- [ ] **Step 3: Commit** `feat(avatar): Avatar interface`.

---

## Task 3: `ClientPlayerAvatar` (passthrough)

**Files:** Create `common/.../bot/movement/ClientPlayerAvatar.java`

- [ ] **Step 1: Implement** — each method maps 1:1 to the CURRENT Walker inline behaviour, so client behaviour is unchanged by construction. Reuse `BotInput`, `AgentInput`, `LookController`, and the Walker's static helpers (make `clientUseItemOn`, `selectBestToolFor`, `aimAtBlockSnap`, `ensureHoldingPlaceableAny` package-visible static or move to a shared `ClientActuation` helper).

Key mappings (mc = Minecraft, p = mc.player):
- `commandMove(l,f)` → `((AgentInput)p.input).commandMove(l,f)` (install AgentInput if absent, as Walker does today).
- `commandJump(v)` → `agentJump` logic; `commandSneak(v)` → `agentSneak`.
- `setSprinting` → `p.setSprinting`; `setYaw` → `p.setYRot + yHeadRot + yBodyRot`; `setPitch` → `p.setXRot`; `requestLookSnap` → `LookController.requestSnap()`.
- `placeSupport(cell,face)` → `clientUseItemOn(mc,p,cell,face)`; `selectToolFor` → `selectBestToolFor(mc,cell)`; `breakHold(v)` → `mc.options.keyAttack.setDown(v)`; `aimAtBlock` → `aimAtBlockSnap(p,cell)`; `holdPlaceable` → `ensureHoldingPlaceableAny(mc)`.
- reads → `p.getX()` etc.; `capabilities()` → `BodyCapabilities.PLAYER`.

- [ ] **Step 2: Compile** `:common:compileJava` → EXIT 0.
- [ ] **Step 3: Commit** `feat(avatar): ClientPlayerAvatar passthrough`.

---

## Task 4: Migrate `Walker` to `Avatar`

**Files:** Modify `common/.../bot/movement/Walker.java`, `common/.../bot/BotApiImpl.java`

- [ ] **Step 1:** Change `public Step tick(Minecraft mc, WorldView world)` → `public Step tick(Avatar a, WorldView world)`. Where a few helpers still need `Minecraft` (e.g. `clientUseItemOn`), they now live behind `ClientPlayerAvatar`; the Walker calls `a.placeSupport(...)` etc.
- [ ] **Step 2:** Mechanically replace the ~87 sites: `mc.options.keyXXX.setDown` / `BotInput.*` / `p.setYRot` / `p.getX()` / `clientUseItemOn` / `selectBestToolFor` / `aimAtBlockSnap` / `ensureHoldingPlaceableAny` → `a.*`. Keep the strafe→commandMove camera-decouple math unchanged (it reads `a.yRot()`).
- [ ] **Step 3:** In `BotApiImpl.clientTick()` (and any other Walker.tick caller), build `Avatar a = new ClientPlayerAvatar(mc);` and call `walker.tick(a, world)`.
- [ ] **Step 4: Compile** `:common:compileJava` → EXIT 0.
- [ ] **Step 5: Regression GameTest** `./gradlew :neoforge:runGameTestServer` → **107/108 + pinchArena PASS** (yaml flake only).
- [ ] **Step 6: Live smoke (zero-regression oracle)** — rebuild+relaunch client, issue a short `goto`, confirm the `[walker]` trace still drives movement (hSpd>0, advances). Δ=0 vs prior behaviour (passthrough).
- [ ] **Step 7: Commit** `refactor(walker): drive via Avatar seam (ClientPlayerAvatar passthrough)`.

---

## Task 5: `LevelWorldView` (live mutable server view)

**Files:** Create `common/.../bot/world/LevelWorldView.java`

- [ ] **Step 1: Implement** `implements WorldView` over a `ServerLevel` + a controlled `LivingEntity` (for inventory/effects in breakCost). Mirror `ClientWorldView`'s solidity/passable/hazard/climbable logic but read `level.getBlockState`. `canPlace()` = true; `placeableBlockCount()`/`hasPlaceableBlock()` scan the entity's inventory (Player.getInventory or LivingEntity hand). `breakCost` = rawBreakCost from block hardness + the entity's tool (finite when allowBreak). Mutations: the view reads the live `ServerLevel`, so blocks the Avatar places/breaks are seen next tick automatically (no caching).
- [ ] **Step 2: Compile** `:common:compileJava` → EXIT 0.
- [ ] **Step 3: Commit** `feat(worldview): LevelWorldView (live server, break/place)`.

---

## Task 6: `ServerPlayerAvatar` (FakePlayer + physics)

**Files:** Create `neoforge/src/main/java/net/magicterra/worlddriver/neoforge/sim/ServerPlayerAvatar.java`

- [ ] **Step 1: Implement** `implements Avatar` wrapping a `FakePlayer fp` (from `FakePlayerFactory.getMinecraft(serverLevel)` or `get(level, profile)`).
  - movement writes store impulse: `commandMove(l,f)` → `pendingLeft=l; pendingForward=f`; `setYaw` → `fp.setYRot(yaw); fp.yBodyRot=yaw; fp.yHeadRot=yaw`; `commandJump`/`commandSneak`/`setSprinting` → store flags / `fp.setSprinting`.
  - `placeSupport(cell,face)` → build `BlockHitResult` on `face` of `cell`, call `fp.gameMode.useItemOn(fp, level, fp.getMainHandItem(), InteractionHand.MAIN_HAND, hit)`.
  - `breakHold(v)`+`aimAtBlock(cell)` → when held, `fp.gameMode.destroyBlock(cell)` (leaves/soft blocks instant; sufficient per spec §7).
  - `holdPlaceable()` → ensure main hand has a solid BlockItem from inventory (swap if needed); `selectToolFor` → no-op/select best (optional for arena).
  - reads → from `fp`.
  - `step()` (driver, called by the GameTest each tick AFTER Walker.tick): apply faithful physics:
    ```java
    // jump (replicate jumpFromGround effect; gate on grounded)
    if (pendingJump && fp.onGround()) {
        double jp = 0.42 * fp.getBlockJumpFactor() + jumpBoost(fp);
        Vec3 dm = fp.getDeltaMovement();
        fp.setDeltaMovement(dm.x, jp, dm.z);
        if (fp.isSprinting()) { float yawr = fp.getYRot()*0.017453292f;
            fp.setDeltaMovement(fp.getDeltaMovement().add(-Math.sin(yawr)*0.2, 0, Math.cos(yawr)*0.2)); }
        fp.hasImpulse = true;
    }
    fp.xxa = pendingLeft * (pendingSneak?0.3f:1f);
    fp.zza = pendingForward * (pendingSneak?0.3f:1f);
    fp.setShiftKeyDown(pendingSneak);
    fp.travel(new Vec3(fp.xxa, 0, fp.zza));     // applies friction/gravity/rotate-by-yRot + move()
    fp.tick();                                   // base ticks (water state, etc.) — or selectively
    ```
    Tune in Task 7 against the parity gate; if `travel` alone underperforms, call `fp.aiStep()` instead (validated by parity).
- [ ] **Step 2: Compile** `./gradlew :neoforge:compileJava -q` → EXIT 0.
- [ ] **Step 3: Commit** `feat(avatar): ServerPlayerAvatar (FakePlayer + manual physics)`.

---

## Task 7: `SimPhysicsParityTest` (@GameTest gate)

**Files:** Create `neoforge/.../neoforge/SimPhysicsParityTest.java` (or add `@GameTest physicsParity` to `AgentGameTest`)

- [ ] **Step 1: Write the test** — spawn FakePlayer in `helper.getLevel()` over a flat floor; drive via ServerPlayerAvatar.step():
  1. flat sprint forward 20 ticks → assert horizontal displacement ∈ expected vanilla band (compute reference once, then assert within 1e-2).
  2. +1 step in front → after driving forward, assert `fp.getY()` rose by 1.0 (auto step-up).
  3. standing jump (commandJump, no move) → assert peak `fp.getY()-startY` ∈ [1.20, 1.30] (apex ≈1.25).
- [ ] **Step 2: Run** `./gradlew :neoforge:runGameTestServer` → physicsParity PASS. If FAIL, adjust the Task 6 driver (travel vs aiStep, jump power) until PASS. **Gate: do not proceed until green.**
- [ ] **Step 3: Commit** `test(sim): FakePlayer physics-parity gate`.

---

## Task 8: `SummitArenaTest` (@GameTest canopy reach)

**Files:** Create `neoforge/.../neoforge/SummitArenaTest.java` (or add `@GameTest summitArena`)

- [ ] **Step 1: Build structure** in `helper.getLevel()`: a small platform; a 1-wide pillar start; an oak-leaf canopy where a `y+2` cardinal-neighbour leaf caps a pillar rung (reproducing the live canopy-clip); a goal cell one rung up/over. Give the FakePlayer 64 dirt + allowBreak on.
- [ ] **Step 2: Run the real Walker** through `ServerPlayerAvatar` + `LevelWorldView` toward the goal; each tick: `walker.tick(avatar, levelWorldView); avatar.step();` for ≤ 400 ticks.
- [ ] **Step 3: Assert** the FakePlayer foot reaches the goal cell (within radius) AND no single path node held > 120 ticks (no permanent bob). With the canopy-pillar `toBreak` fix active, the leaf is cleared and the rung completes.
- [ ] **Step 4: Run** `./gradlew :neoforge:runGameTestServer` → summitArena PASS.
- [ ] **Step 5: Commit** `test(sim): canopy-pillar summit arena (real Walker headless)`.

---

## Task 9: Full regression + e2e sign-off

- [ ] **Step 1:** `./gradlew :neoforge:runGameTestServer` → **107/108 + pinchArena + physicsParity + summitArena PASS** (yaml flake only).
- [ ] **Step 2:** Live client smoke (ClientPlayerAvatar path): relaunch, `goto`, confirm smooth movement (zero client regression).
- [ ] **Step 3: Commit** `chore: Phase 0+1 Avatar abstraction e2e green`.

---

## Self-Review notes
- Spec coverage: §3 → Tasks 7,8; §4 → Tasks 1–6; §6 testing → Tasks 4(5,6),7,8,9; §7 risk (physics) → Task 7 gate, (mining deferred) → arena uses place + instant-break only.
- Type consistency: `Avatar` method names used identically in ClientPlayerAvatar (T3), Walker (T4), ServerPlayerAvatar (T6). `step()` is ServerPlayerAvatar-only (driver), not on the interface.
- Known risk: Walker's static client helpers must be exposed to ClientPlayerAvatar (make package-private static or extract `ClientActuation`); the strafe→commandMove camera-decouple math stays in Walker reading `a.yRot()`.
