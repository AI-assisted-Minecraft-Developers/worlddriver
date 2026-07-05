# Dynamic Entity-Anchor Leash + Follow Profile (Phase A3a) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `goto ... leash:{entity:'PlayerB',radius,weight}` / `leashHard:{entity:...}` tether the route to a MOVING entity (带路B别离太远), and `mc.bot.follow` accepts the intent-layer bias/capability/constraint args (跟A但 forbidWater 等组合).

**Architecture:** Dynamism lives in `IntentProcess`'s tick re-solve, NOT inside modifiers (CostModifier/Constraint stay immutable — a mid-search moving anchor would break search coherence). Each dirty check (anchor block moved >2 OR first solve; rate-limited ≥20 ticks) re-finds the entity, rebuilds the `SearchProfile` with a fresh `LeashAnchor`/`LeashHardRadius` at the entity's position, then `walker.setSearchProfile(...)` + `walker.forceRepath()` — the exact `FollowProcess` dirty pattern (`lastTargetBlock`/`REPLAN_TICKS`). Entity lookup is `FollowProcess.findTarget`'s proven `Level.getEntities` scan, extracted to a shared helper. Follow-profile is a pass-through: `FollowProcess` gains an optional `SearchProfile` forwarded to its walker.

**Tech Stack:** Java 21 multi-loader; A2a `SearchProfile` channel; `serverProcessArena` precedent for a ticked-process GameTest.

## Global Constraints

- **Modifiers stay immutable**; the ONLY dynamic seam is the IntentProcess re-solve (rebuild + swap profile + forceRepath).
- **Absent `entity` key = byte-identical**: static `leash`/`leashHard` x/y/z parsing unchanged; `Intent` without an `EntityLeash` never enters the re-solve block.
- **Entity-not-found is tolerated, not fatal**: keep the last anchor (stale) and keep walking — mirrors FollowProcess's idle tolerance. Never fail the goto because the anchor de-spawned momentarily.
- **YAGNI:** dynamic anchor for leash ONLY (no dynamic avoid/preferY, no dynamic TARGET — that plus riverbank is A3b). The spec's "snapshot intent.target()" seam stays untouched (target is still static/idempotent this phase).
- Arena style: the re-solve loop needs a TICKED process → model on `serverProcessArena` (AgentGameTestServer — the one reliable process-path arena), NOT a planner-only assert. Pin+restore any BotConfig used.

## File Structure

- **Create** `common/.../bot/process/EntityLeash.java` — record spec of the dynamic anchor.
- **Create** `common/.../bot/process/EntityFind.java` — shared entity lookup (extracted findTarget logic).
- **Modify** `common/.../bot/process/Intent.java` — optional `EntityLeash entityLeash` (5-arg ctor; existing ctors default null).
- **Modify** `common/.../bot/process/IntentProcess.java` — the re-solve block in `tick`.
- **Modify** `common/.../bot/process/FollowProcess.java` — optional `SearchProfile` ctor param → walker.
- **Modify** `common/.../bot/GotoGoalResolver.java` — `resolveEntityLeash(Params)`.
- **Modify** `common/.../bot/BotApiImpl.java` — goto passes EntityLeash; follow parses+passes SearchProfile.
- **Modify** `common/.../mcp/catalog/BotTools.java` — `entity` prop on leash/leashHard; follow gains the profile args.
- **Modify** `neoforge/.../AgentGameTestServer.java` — `entityLeashRepathArena`.

---

### Task 1: EntityLeash + EntityFind + Intent field + IntentProcess re-solve

**Files:** the four `process` files above.

**Interfaces:**
- Produces: `record EntityLeash(String entity, double radius, double weight, boolean hard)`; `EntityFind.nearest(Level lvl, Player self, String nameOrType) → @Nullable Entity`; `Intent(Goal, List<CostModifier>, CapabilityProfile, List<Constraint>, EntityLeash)` + `entityLeash()` accessor.

- [ ] **Step 1: `EntityLeash.java`**
```java
package net.magicterra.agent.bot.process;

/** Spec of a DYNAMIC leash anchor: tether the route near a moving entity.
 *  {@code entity} is a player/entity name (no colon) or an entity type id
 *  (with colon, e.g. "minecraft:armor_stand"). {@code hard} picks
 *  LeashHardRadius (prune) vs LeashAnchor (soft cost). Resolved per re-solve
 *  by {@link IntentProcess} — never inside the immutable search. */
public record EntityLeash(String entity, double radius, double weight, boolean hard) {}
```

- [ ] **Step 2: `EntityFind.java`** — transcribe `FollowProcess.findTarget` (lines 157-177) generalized: param `String nameOrType`; if it contains `':'` match `BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString()`, else match `e.getName().getString()` exactly; same 96-block AABB `lvl.getEntities(self, box, x -> true)` nearest-wins loop. Javadoc: works on ClientLevel AND ServerLevel (EntityGetter), unlike entitiesForRendering.

- [ ] **Step 3: `Intent.java`** — add `private final EntityLeash entityLeash;` + accessor + 5-arg ctor; the 1/2/4-arg ctors delegate with `null`. No copy needed (record immutable).

- [ ] **Step 4: `IntentProcess` re-solve.** Fields:
```java
    private BlockPos lastAnchor;           // last solved anchor block (null = not yet solved)
    private int ticksSinceAnchorSolve;     // rate limiter
    private static final int ANCHOR_RESOLVE_MIN_TICKS = 20;
    private static final double ANCHOR_DIRTY_DIST_SQ = 2 * 2;
```
At the TOP of `tick(...)`, before `walker.tick`:
```java
        EntityLeash el = intent.entityLeash();
        if (el != null && ++ticksSinceAnchorSolve >= ANCHOR_RESOLVE_MIN_TICKS || (el != null && lastAnchor == null)) {
            Player self = a.player();
            Entity anchor = (self != null) ? EntityFind.nearest(self.level(), self, el.entity()) : null;
            if (anchor != null) {
                BlockPos ab = anchor.blockPosition();
                if (lastAnchor == null || ab.distSqr(lastAnchor) > ANCHOR_DIRTY_DIST_SQ) {
                    lastAnchor = ab;
                    ticksSinceAnchorSolve = 0;
                    walker.setSearchProfile(profileWith(el, ab));
                    walker.forceRepath();
                }
            }
            // anchor==null → stale lastAnchor keeps governing; tolerated, not fatal.
        }
```
(NOTE the boolean precedence bug bait in the sketch above — write it as two clear nested ifs: `if (el != null) { ticksSinceAnchorSolve++; if (lastAnchor == null || ticksSinceAnchorSolve >= ANCHOR_RESOLVE_MIN_TICKS) { ...find+dirty-check... } }`.) Helper:
```java
    /** Rebuild the search profile with a leash modifier/constraint at the anchor's
     *  CURRENT cell centre — the only mutable seam of the dynamic anchor. */
    private SearchProfile profileWith(EntityLeash el, BlockPos anchor) {
        double ax = anchor.getX() + 0.5, ay = anchor.getY(), az = anchor.getZ() + 0.5;
        List<CostModifier> bias = intent.bias();
        List<Constraint> cons = intent.constraints();
        if (el.hard()) {
            List<Constraint> c2 = new ArrayList<>(cons);
            c2.add(new LeashHardRadius(ax, ay, az, el.radius()));
            return new SearchProfile(bias, intent.capability(), c2);
        }
        List<CostModifier> b2 = new ArrayList<>(bias);
        b2.add(new LeashAnchor(ax, ay, az, el.radius(), el.weight()));
        return new SearchProfile(b2, intent.capability(), cons);
    }
```
Also: the ctor's initial `walker.setSearchProfile(intent.searchProfile())` stays (covers el==null; when el!=null the first tick's re-solve swaps in the anchored profile before much walking happens).

- [ ] **Step 5: `FollowProcess` profile pass-through.** Add ctor overload `FollowProcess(String entityType, String name, int radius, int maxIdleTicks, SearchProfile profile)` → `walker.setSearchProfile(profile==null?SearchProfile.NONE:profile)`; existing ctor delegates with `SearchProfile.NONE`.

- [ ] **Step 6: compile + commit** — `./gradlew :common:compileJava --console=plain` → BUILD SUCCESSFUL; `git add -A && git commit -m "process: dynamic entity-anchor leash re-solve in IntentProcess; FollowProcess takes a SearchProfile (A3a Task 1)"`

---

### Task 2: verb parsing + schema

**Files:** `GotoGoalResolver.java`, `BotApiImpl.java`, `BotTools.java`.

**Interfaces:** Consumes Task 1 types. Produces `GotoGoalResolver.resolveEntityLeash(Params) → @Nullable EntityLeash`.

- [ ] **Step 1: `resolveEntityLeash`.** In GotoGoalResolver:
```java
    /** leash:{entity:'X',radius?,weight?} / leashHard:{entity:'X',radius} → dynamic anchor.
     *  When the entity key is present the STATIC x/y/z parse is skipped for that key
     *  (dynamic wins); absent → null and the static path runs exactly as before. */
    static EntityLeash resolveEntityLeash(Params p) {
        if (p.get("leash") instanceof Map<?, ?> l && l.get("entity") instanceof String e && !e.isBlank()) {
            double radius = Params.toDouble(l.get("radius"), 8.0);
            double weight = Params.toDouble(l.get("weight"), 20.0);
            return new EntityLeash(e, radius, weight, false);
        }
        if (p.get("leashHard") instanceof Map<?, ?> l && l.get("entity") instanceof String e && !e.isBlank()) {
            double radius = Params.toDouble(l.get("radius"), 8.0);
            return new EntityLeash(e, radius, 0, true);
        }
        return null;
    }
```
AND guard the two STATIC parsers so an entity-keyed map doesn't ALSO produce a static modifier/constraint: in `resolveBias`'s `leash` block and `resolveConstraints`'s `leashHard` block, skip when `l.get("entity") instanceof String` (one-line early guard each).

- [ ] **Step 2: goto wiring.** BotApiImpl goto: build the Intent with the 5th arg `GotoGoalResolver.resolveEntityLeash(p)`.

- [ ] **Step 3: follow wiring.** At `BotApiImpl:718` (`new FollowProcess(entityType, name, radius, maxIdleTicks)`): parse a profile from the same Params `p` of the follow verb — `new SearchProfile(GotoGoalResolver.resolveBias(p), GotoGoalResolver.resolveCapability(p), GotoGoalResolver.resolveConstraints(p))` — and pass via the new 5-arg ctor. (Resolver methods are package-private static in the same package — verify visibility; widen to public static if BotApiImpl is another package... it is the SAME package `net.magicterra.agent.bot` — fine.)

- [ ] **Step 4: schema.** BotTools goto block: add `.prop("entity", string())` inside BOTH the `leash` and `leashHard` object schemas; extend their help lines: "or entity:'name-or-type' → DYNAMIC anchor that follows the entity (带路: goto the destination + leash:{entity:'PlayerB'})". Follow tool block: add the same `avoid`/`preferY`/`leash`/`forbidParkour`/`yFloor`/`yCeil`/`forbidWater`/`forbidDig` props as goto (copy the prop lines) + one help line "accepts goto's bias/constraint args (forbidWater etc.) applied to the follow pathing".

- [ ] **Step 5: compile + commit** — `git add -A && git commit -m "goto/follow: entity-anchor leash arg + follow accepts intent profile args (A3a Task 2)"`

---

### Task 3: ticked-process arena + live 带路 + CHANGELOG

**Files:** `neoforge/.../AgentGameTestServer.java` (add `entityLeashRepathArena`), CHANGELOG.

- [ ] **Step 1: arena.** Model setup on the existing `serverProcessArena` in the same file (ServerPlayerAvatar + IntentProcess ticked to completion). Geometry: flat stone lane ~30 long (fresh coords). Spawn an `armor_stand` at the START. Build `Intent` with `Goal.Block(goalPos 24 blocks away)` + `EntityLeash("minecraft:armor_stand", 8, 0, true)` (HARD leash → crisp semantics). Tick the process: phase 1 — with the stand at start, the bot must STALL near the 8-radius edge (assert after ~200 ticks: not arrived, bot within radius+2 of the stand). Phase 2 — teleport the stand to the goal (`stand.teleportTo(...)`), keep ticking: the re-solve must pick up the moved anchor (≤20-tick rate limit) and the bot ARRIVES (assert within ~600 more ticks). This proves the whole dynamic chain (find → dirty → rebuild → forceRepath). Log phase outcomes. NOTE: this ticks a real process — serverProcessArena is the reliable precedent; if phase-1 stall assertion proves flaky in practice, weaken phase 1 to "not arrived yet" only (the ARRIVED-after-move is the core signal).
- [ ] **Step 2: run headless** (confirm no gametest server alive first), both this arena and the whole prior set green-or-known-flaky; the new arena must pass.
- [ ] **Step 3: live 带路 scenario** (client is up): summon an armor stand named anchor near the bot, `goto` a far point with `leash:{entity:'minecraft:armor_stand',radius:6,weight:25}` (SOFT for live), tp the stand forward in 2-3 hops, verify the bot advances leash-bound and arrives; screen-watch stays on. Verify via observe.player positions relative to the stand.
- [ ] **Step 4: CHANGELOG** `[Unreleased] ### Added`: "`mc.bot.goto` leash/leashHard accept `entity:'name-or-type'` — a DYNAMIC anchor re-solved as the entity moves (带路 scenarios); `mc.bot.follow` accepts the goto bias/constraint args (compose 'follow A but forbidWater/avoid zones')."
- [ ] **Step 5: commit** — arena+changelog commits as usual.

---

## Self-Review

**Coverage:** 带路B别太远 (dynamic leash, Tasks 1-3) ✓; 跟A+约束组合 (follow profile, Tasks 1-2) ✓; A3b (dynamic TARGET, riverbank) correctly deferred; target-snapshot seam untouched (still static) per YAGNI note.
**Placeholders:** Task 1 Step 4 flags the precedence-bug bait and prescribes the nested-if form; Task 2 Step 3 pre-answers the visibility question (same package). Task 3 marks the phase-1 assertion's fallback.
**Types:** `EntityLeash(String,double,double,boolean)` consistent across Tasks 1-3; `Intent` 5-arg ctor consumed in Task 2 Step 2; `EntityFind.nearest(Level,Player,String)` consumed in Task 1 Step 4; `FollowProcess` 5-arg ctor consumed in Task 2 Step 3.
