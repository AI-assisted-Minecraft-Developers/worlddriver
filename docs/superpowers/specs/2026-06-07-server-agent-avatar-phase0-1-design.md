# Server-Side Agent Abstraction — `Avatar` (Phase 0 + Phase 1)

**Date:** 2026-06-07
**Status:** Approved design (not yet implemented)
**Scope:** Architecture overview (long-term reference) + Phase 0 (spike) + Phase 1 (extract the `Avatar` seam). Phases 2–4 are listed for context only and will each get their own spec.

---

## 1. Goals & Success Criteria

Today the agent operation layer (Walker/pathfinding, combat, avoidance, building, crafting, mining) only drives the **client** `LocalPlayer`. The long-term goal is to drive **any server-side `LivingEntity`** (a headless `FakePlayer` with full capability; or a zombie/villager with a reduced capability set). This spec delivers the foundation:

- Decouple the **Walker (execution layer)** from `LocalPlayer` so it can run headless on a server `FakePlayer`.
- Deliver a **reproducible headless steep-terrain test bed** that removes the documented "live is non-reproducible" measurement ceiling (client only sees loaded chunks → every relaunch re-routes and stalls in a different, non-reproducible spot).

**Success criteria (Phase 0 + 1):**

1. **Physics-parity gate passes** — a `FakePlayer` driven by manual `travel()`+`move()` reproduces vanilla movement numbers (flat sprint displacement, +1 step-up, standing-jump apex) within a tight tolerance.
2. **Canopy-pillar arena reach** — the **real** `Walker`, driven through a `ServerPlayerAvatar` over a live `LevelWorldView`, reaches the goal in a synthetic steep structure that reproduces a real execution case.
3. **Zero client regression** — the client path runs through `ClientPlayerAvatar` as a 1:1 passthrough: `:neoforge:runGameTestServer` stays **107/108 + pinchArena PASS** (the lone failure is the known `34_yaml_gametest` flake), and the live client journey is byte-equivalent (Δ = 0).

---

## 2. Architecture Overview (long-term reference)

The full retargeting (all four phases) is built on **four abstraction seams + a takeover SPI + a generalized tick driver**. Only the parts marked *(Phase 0/1)* are implemented in this spec.

| Seam | Purpose | Implementations |
|------|---------|-----------------|
| **`Avatar`** *(Phase 1)* | Actuation + self-state surface over the controlled entity | `ClientPlayerAvatar`, `ServerPlayerAvatar` *(Phase 0/1)*; `MobAvatar` *(Phase 4)* |
| **`WorldView`** *(exists; extended in Phase 1)* | Block/world perception | `ClientWorldView`, `ServerWorldView` (read-only, exists), `GridWorldView` (synthetic), **`LevelWorldView`** (new, live mutable) |
| **`EntitySense`** *(Phase 3)* | Threat / nearby-entity perception | `ClientSense` (rendering list + creeper swell), `ServerSense` (`ServerLevel.getEntities`, authoritative + `isIgnited`) |
| **`BodyCapabilities`** *(Phase 1 minimal)* | Capability flags so the orchestrator skips Player-only chains and the planner reads entity movement caps | `canPlace/canCraft/canSprint/jumpHeight/stepHeight/aabbWidth` |

**`MobTakeover` SPI** *(Phase 4)* — registry keyed by `EntityType`. `possess()` strips AI **once** (not per-tick): Goal mobs via `goalSelector.removeAllGoals` (snapshot for `release()`); Brain mobs via a `BrainAccessor` Mixin that clears `availableBehaviorsByPriority` + `sensors` once (afterwards `Brain.tick()` iterates empty maps → naturally idle, no per-tick fighting). Third-party mods register their own handler; the core never hard-codes specific mob types.

**Tick driver generalization** *(Phase 3+)* — the body of `BotApiImpl.clientTick()` becomes a pure `agentTick(Avatar, WorldView, EntitySense, BotState)`. The client tick event keeps calling it; a server-tick driver calls it per registered agent-entity and then manually runs the physics step (after the agent sets impulse, call `travel`+`move`).

**Capability iron law (vanilla, verified):** `crafting / container menus / block placement via useItemOn / recipe book / elytra / eating` are **`Player`-only** (they go through `AbstractContainerMenu` / `ServerGameMode`). A non-player mob has none of these subsystems. Therefore: a `FakePlayer` (a `ServerPlayer`) has **full** capability server-side; a plain mob is limited to movement / combat / perception / direct-world building & mining, with crafting/containers gated off.

---

## 3. Phase 0 — Spike (physics fidelity + test bed)

> Implementation order note: the spike runs the *real* Walker, so it depends on `ServerPlayerAvatar` + `LevelWorldView` from §4. Build the §4 interface/impls first, then these two tests.

New, in the **neoforge** module (GameTest side):

### 3.1 `SimPhysicsParityTest` (`@GameTest`)
Create a `FakePlayer` via `FakePlayerFactory`. Drive it with **Approach A** (manual physics): each tick set `fp.zza/xxa`, `fp.jumping`, `fp.setSprinting(...)`, `fp.setYRot(...)`, then call `fp.travel(impulseVec)` followed by `fp.move(MoverType.SELF, fp.getDeltaMovement())` — the identical `LivingEntity` physics the client runs for `LocalPlayer`. Assert vanilla numbers in three scenarios:

1. Flat sprint walk for N ticks → expected horizontal displacement.
2. +1 `stepUp` → auto-climbs onto the step.
3. Standing jump → apex height ≈ 1.25 (same source as `ClientWorldView.jumpApexBlocks`).

Tolerance is tight (e.g. 1e-3). Failure to match = hard fail. **This is the gate that must pass before any harder case is trusted.**

### 3.2 `SummitArenaTest` (`@GameTest`)
Build a structure in `helper.getLevel()` reproducing the real canopy-clip geometry observed live (an oak-leaf canopy with a central pillar column whose cardinal neighbour at `y+2` holds a leaf that caps the jump). Give the `FakePlayer` a stack of dirt. Run the **real `Walker`** (through `ServerPlayerAvatar` + a live `LevelWorldView`) for ≤ N ticks. Assert: (a) reaches the goal cell; (b) no bob longer than K ticks on any single node.

This simultaneously proves **physics fidelity** and **traversal of real terrain headlessly**, and becomes the deterministic A/B bed for execution-layer fixes (toggle a fix → observe reach / bob-tick change).

---

## 4. Phase 1 — Extract the `Avatar` seam

### 4.1 `Avatar` interface (common)
Contains **only the surface the Walker actually uses** in Phase 0/1 (combat/craft/equip methods are deferred — their processes don't run here):

- **Reads:** `x() y() z()`, `deltaMovement()`, `yRot() xRot()`, `onGround()`, `inWater() underWater()`, `boundingBox()`, `eyePos()`, `heldItem()`, `flying()`.
- **Movement writes:** `commandMove(left, fwd)`, `commandJump(boolean)`, `commandSneak(boolean)`, `setSprinting(boolean)`, `setYaw(float)`, `setPitch(float)`, `requestLookSnap()`.
- **Block writes:** `holdPlaceable() -> boolean`, `placeSupport(BlockPos cell, Direction face)`, `selectToolFor(BlockPos cell)`, `breakHold(boolean)`, `aimAtBlock(BlockPos cell)`.
- **Capabilities:** `capabilities() -> BodyCapabilities`.

### 4.2 Implementations
- **`ClientPlayerAvatar`** (common): maps every method **1:1 to the current behaviour** — `mc.options.keyXXX` / `AvatarInput` / `p.setYRot` / `clientUseItemOn` / `BotInput`. Pure passthrough, **no behaviour change** → this is the regression oracle. (Common already references client classes, as `ClientWorldView` does today.)
- **`ServerPlayerAvatar`** (**neoforge** module, because `FakePlayer` is NeoForge-specific): impulse → entity fields; `placeSupport` → `fp.gameMode.useItemOn` (a `FakePlayer` is a `ServerPlayer`, so real placement works); `selectToolFor`/`holdPlaceable` → server-side inventory swaps. Its physics step is driven by the §3 driver. (Cross-platform Fabric equivalent is out of scope here.)

### 4.3 Changes to existing code
- `Walker.tick(Minecraft, WorldView)` → `Walker.tick(Avatar, WorldView)`; mechanically migrate the ~87 client call sites to `avatar.*`.
- `BotApiImpl.clientTick()` constructs a `ClientPlayerAvatar(mc)` and calls `Walker.tick(avatar, world)` — the client entry point is otherwise unchanged.
- **Extend `WorldView`:** add a live **`LevelWorldView`** (reads a `ServerLevel`; supports break/place; `breakCost` reads the `FakePlayer`'s inventory/effects) for the spike. `ServerWorldView` stays read-only and unchanged.

---

## 5. Data Flow (Phase 0/1)

```
tick
 → construct Avatar + WorldView
 → Walker.tick(avatar, world)
 → Walker calls avatar.* actuation
 → [client] vanilla LocalPlayer tick runs physics
   [server] §3 driver manually runs travel()+move()
 → next tick
```

---

## 6. Testing

- **New deterministic assertions:** physics-parity gate (§3.1) + canopy-arena reach/no-bob (§3.2).
- **Regression oracle:** `./gradlew :neoforge:runGameTestServer` stays **107/108 + pinchArena PASS** (yaml flake excepted). The live client, routed through `ClientPlayerAvatar`, is **byte-equivalent** to current behaviour (Δ = 0) — verified by the existing `[walker]` trace and goto behaviour.

---

## 7. Risks & Boundaries

- **Physics fidelity (largest risk):** the manual `travel`+`move` sequence must match the client tick exactly (jump initial velocity 0.42, sprint friction, step height). Mitigation: the §3.1 parity gate blocks all downstream work until it matches.
- **Module placement:** `ServerPlayerAvatar` lives in `neoforge` (FakePlayer is NeoForge-specific). A Fabric equivalent is deferred.
- **Mining:** `MineProcess` relies on vanilla `continueDestroyBlock` for completion detection (it deliberately uses `mc.options.keyAttack`). Phase 0/1 does **not** migrate `MineProcess`; the canopy arena only needs *place* (pillar), and any *break* goes through `LevelWorldView` as a simple removal. A full server-side break-progress model is deferred to Phase 2.

---

## 8. Out of Scope (this spec)

Phases 2–4 (`EntitySense`/`ServerSense`, `MobAvatar`, `MobTakeover`, the `BrainAccessor` Mixin), the combat/craft/equip `Avatar` methods, the cross-platform Fabric `ServerPlayerAvatar`, and the server-side break-progress model.

---

## Appendix A — Verified codebase facts (informing this design)

- `WorldView` is the proven perception seam; **three** impls already exist: `ClientWorldView`, `bot/world/ServerWorldView.java` (read-only, used by `ObserveApi` server-side), `bot/debug/GridWorldView.java` (synthetic).
- `bot/movement/BotInput.java` is already a thin movement-actuation facade, but hard-wired to `LocalPlayer` + `AvatarInput` + `Minecraft.getInstance()`. It is the natural seed for `Avatar`'s movement methods. (Attack/use deliberately bypass it — mining rides `mc.options.keyAttack` for the vanilla destroy pipeline.)
- `Walker.tick(Minecraft mc, WorldView world)` then `LocalPlayer p = mc.player;` — all actuation flows from `mc` / `p` / `mc.options.keyXXX` / `clientUseItemOn(mc,p,...)` across ~87 sites.
- Combat `ThreatScanner` reads `mc.level.entitiesForRendering()` + client creeper swell (deeply client-bound today) → replaced by `ServerSense` in Phase 3; the server entity list is authoritative and *more* complete.
- NeoForge `net.neoforged.neoforge.common.util.FakePlayerFactory` provides a `ServerPlayer` usable server-side (full Player capability) but its physics are **not** auto-simulated by the server — it must be driven manually (Approach A).
