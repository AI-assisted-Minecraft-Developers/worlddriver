# The priority-chain scheduler, as originally proposed

> **Archived proposal, written 2026-06-04, in Chinese. Not a description of current behaviour.**
>
> This is the original argument for replacing the single overwriting process slot with a set of
> chains that bid a priority every tick, and for keeping the hand and equipment behaviours out of
> that competition under channel ownership instead. The scheduler was built along these lines and
> lives in `bot/scheduler/`.
>
> Read it for the reasoning, not for the shape. The interfaces here take a `Minecraft` where the
> shipped ones take a `Body`; the priority table here is shorter than the shipped one; and the
> document's promise that shelter-seeking would sit below the user task and never preempt it was
> deliberately broken later, after a bot walking somewhere at nightfall turned out to be unable to
> save itself.
>
> The current design, including the five semantic defects found by running this model for a while,
> is `docs/design/scheduler-semantics.md`.

> This was the first milestone on the autonomy roadmap and the load-bearing wall under everything
> else on it, so it had to land before any of the rest. The model it borrows from is the task-chain
> system in AltoClef, a third-party Minecraft bot; that source is not part of this repository.

## 1. The problem

At the time this was written the execution model came in two halves, both visible in
`BotApiImpl#clientTick`. What follows describes the code **before** the scheduler landed; that layer
is `bot/scheduler/` today.

- **A foreground process slot.** A single `volatile BotProcess current` running one process at a
  time, where whoever arrived last overwrote whoever was there before.
- **Ambient automatic behaviours.** `AutoEat`, `AutoTool`, `AutoRespawn` and `AutoSwim` under
  `bot/auto/`, each gated by a `BotConfig` switch and by **input-channel ownership** — automatic
  eating stands aside while `processOwnsUseKey` is true, for instance — and each running concurrently
  with the foreground process.
- **The water-bucket fall catch.** `if (CLUTCH.tick(mc, world)) return;` at the very top of
  `clientTick`, which **is already a highest-priority preemption**, expressed as an early return.

Channel ownership plus that early return were therefore already an embryonic form of the model
proposed here. Where the arrangement could not carry autonomy was the **foreground slot**:

- A bot mining when a zombie attacks needs to switch to combat automatically and then **come back and
  keep mining**. An overwriting slot has no way to express coming back.
- Combat needs to preempt the user's task as a complete process and then let that task resume when it
  finishes. An instantaneous early return cannot express that, because it has no notion of suspending
  and resuming.
- A boss fight needs a main combat behaviour with continuous dodging and continuous healing layered
  on top of it.

> **This design changes only the foreground slot.** The overwriting single `current` becomes a
> multi-chain scheduler with preemption and resumption. The ambient behaviours under `bot/auto/` stay
> as they are, gated by channel ownership, and the early-return preemption of the fall catch is
> generalised into a `PRIORITY_PANIC` band inside the scheduler. Nothing that already works is torn
> out.

AltoClef solves this with a `TaskRunner`: **several task chains each report a priority from
`getPriority()` every tick, and only the highest-priority chain runs.** Priority is a function of the
situation. With no hostile mobs around, the defence chain's priority is zero and it does not compete;
the moment one appears its priority climbs above the task chain's and it preempts automatically; when
the fight ends its priority falls back to zero and the suspended task chain resumes on its own.

We adopt that pattern but fit it to our own `BotProcess` system, without taking on Baritone.

## 2. A three-layer execution model

There are two kinds of reflex, and they are separated by whether they contend for the **movement
channel** or for the **hand and equipment channel**:

```
  clientTick():
   ┌─ A. Hand/equipment ambient (bot/auto/, concurrent with movement, not scheduled) ─┐
   │   AutoTotem(off-hand) / AutoShield+AutoEat+AutoHeal(contend for the use key,     │
   │   arbitrated) / AutoTool(hotbar) / AutoEquip                                      │
   │   ── each gated by BotConfig and by channel ownership                             │
   └───────────────────────────────────────────────────────────────────────────────────┘
   ┌─ B. Movement channel, ProcessScheduler.tick: run the highest-priority active chain ┐
   │  PanicChain (creeper evasion / dodge / CLUTCH fall catch)  priority 900–1000        │
   │  RetreatChain (disengage at low health)                    priority 100             │
   │  CombatChain (holds CombatProcess, threat present)          priority 60             │
   │  UserTaskChain (holds the foreground BotProcess: goto/mine/craft)  priority 50      │
   └─────────────────────────────────────────────────────────────────────────────────────┘
```

- **The dividing line** is what a behaviour contends for. Anything that decides where the body moves
  — panic, dodge, retreat, combat, the user's task — bids in the chain competition. Anything that
  produces only a momentary hand or equipment effect — raising a shield, eating or drinking,
  replacing a totem, swapping armour — lives in `bot/auto/` and runs concurrently with movement. This
  is section 6. It reuses the channel gating `AutoEat` already has rather than inventing a second
  mechanism.
- **Timescales.** Reflexes decide locally within a tick and never consult a language model, which is
  what the layering rule in the perception document requires. A skill closes its loop over a few
  seconds. An external language model sets *intent* through `mc.bot.*` and never participates in a
  tick.

## 3. The interfaces

`BotProcess` stays as it is — there are already eighteen implementations and none of them is
rewritten:

```java
public interface BotProcess {
    String kind();
    void attach(BotState st);
    boolean tick(Minecraft mc, WorldView w, BotState st);  // true = finished
}
```

The chain abstraction is new:

```java
public interface Chain {
    String name();
    /** Evaluated once per tick. Returning <= 0 means "not bidding". Higher wins. */
    float priority(Minecraft mc, WorldView w, BotState st);
    /** Called when the scheduler selects this chain. */
    void tick(Minecraft mc, WorldView w, BotState st);
    /** Called when a higher-priority chain preempts this one: save a resume point, release keys. */
    default void onInterrupt(Chain by) {}
    /** Called when this chain regains control after being suspended. */
    default void onResume() {}
}
```

And the scheduler:

```java
public final class ProcessScheduler {
    private final List<Chain> chains;      // registration order is irrelevant; selection is by priority
    private Chain current;
    private float lastPriority;            // for hysteresis

    public void tick(Minecraft mc, WorldView w, BotState st) {
        Chain best = null; float bestP = 0f;
        for (Chain c : chains) {
            float p = c.priority(mc, w, st);
            if (p > bestP) { bestP = p; best = c; }
        }
        // Hysteresis: stop two chains with near-equal bids from trading the body every tick
        if (current != null && best != current
            && bestP < lastPriority + HYSTERESIS) {
            best = current; bestP = lastPriority;
        }
        if (current != null && best != current) current.onInterrupt(best);
        if (best != null && best != current) best.onResume();
        current = best; lastPriority = bestP;
        if (best != null) best.tick(mc, w, st);
    }
}
```

> `HYSTERESIS` corresponds to AltoClef's `cachedLastPriority` damping, which its `MobDefenseChain`
> uses.

## 4. Two concrete chains

### 4.1 `UserTaskChain`, the baseline

This wraps whatever the current foreground task is. `mc.bot.goto`, `mc.bot.mine`, `mc.bot.craft` and
the rest no longer set `current` directly; they call `userTaskChain.setProcess(p)`.

```java
final class UserTaskChain implements Chain {
    private BotProcess process;
    public void setProcess(BotProcess p) { this.process = p; p.attach(state); }
    public float priority(...) { return process != null ? PRIORITY_USER : 0f; }  // 50, say
    public void tick(Minecraft mc, WorldView w, BotState st) {
        if (process != null && process.tick(mc, w, st)) process = null;  // clear on completion
    }
    public void onInterrupt(Chain by) { Walker.releaseKeys(); }  // release WASD, stop where we are
    public void onResume() { Walker.forceRepath(); }             // replan on resume; never reuse a stale path
}
```

The important part is that `onResume` **forces a replan**. While the chain was preempted the bot may
have been knocked back and the terrain may have changed, and reusing the old path walks into a wall.
This is the first entry in the roadmap's risk table.

### 4.2 The movement-channel reflex chains: `PanicChain`, `RetreatChain`, `DodgeChain`

One instance per reflex, each with a `priority` that is a function of the situation. Hand and
equipment reflexes do not appear here; see section 6.

```java
final class RetreatChain implements Chain {
    public float priority(Minecraft mc, WorldView w, BotState st) {
        float hp = mc.player.getHealth();
        if (hp >= BotConfig.retreatHpThreshold) return 0f;     // not bidding
        return PRIORITY_SURVIVAL + (BotConfig.retreatHpThreshold - hp);  // the weaker, the more urgent
    }
    public void tick(...) { /* trigger the equivalent of RunAwayProcess */ }
}
```

The proposed priority bands, as constants:

| Band | Value | Who (movement-channel chains only) |
|---|---|---|
| `PRIORITY_PANIC` | 900–1000 | PanicChain: evading a creeper's detonation; DodgeChain evading a projectile; CLUTCH catching a fall |
| `PRIORITY_SURVIVAL` | 100 | RetreatChain, disengaging at low health |
| `PRIORITY_COMBAT` | 60 | CombatChain, when a threat is present |
| `PRIORITY_USER` | 50 | UserTaskChain, the user's foreground task |

> Behaviours such as automatic shield, totem and healing — the "defend yourself while still working"
> kind — are **deliberately absent from this table**. They are hand and equipment ambient behaviours
> under `bot/auto/`, they run concurrently with movement, and they never bid for the movement
> channel. See section 6.

## 5. Migrating the existing code

| Today | Becomes |
|---|---|
| the single-process field `BotApiImpl.current` | `ProcessScheduler` holding several chains; `UserTaskChain` holds the single process |
| `startProcess(p)` replacing `current` | `userTaskChain.setProcess(p)` |
| `BotApiImpl.clientTick` calling `current.tick()` directly | `scheduler.tick()` |
| `mc.bot.status` reporting one process | reporting the active chain, the suspended user process, and each chain's priority |
| `mc.bot.cancel{kind}` | cancels the corresponding chain's process; ambient behaviours under `bot/auto/` cannot be cancelled, only switched off with `setting` |

The external API **does not change behaviour**: `mc.bot.goto` still returns `{started:true}` and is
still polled through `mc.bot.status`. Status simply gains states such as "combat preempted me, I am
suspended", so that an external caller can understand why a task paused.

## 6. The hard part: layering versus preemption

A pure "highest priority takes everything" model has a hole in it: **raising a shield or replacing a
totem should happen while the body keeps moving**, not instead of moving. The good news is that
**the existing code already solves this with channel ownership.** `AutoEat` runs only when no process
holds the use key; `AutoTool` runs only when no process holds the hotbar. We reuse that rather than
inventing a second concept:

- **The chain scheduler arbitrates only who owns the movement channel** — the preemption and
  resumption of the foreground slot. Retreat, dodge, panic, combat and the user's task bid there.
- **The ambient behaviours under `bot/auto/` keep working exactly as they do now.** The new
  `AutoTotem`, `AutoShield`, `AutoHeal` and `AutoEquip` are siblings in `bot/auto/`, gated by
  `BotConfig` and by channel ownership, running **concurrently with movement** and never bidding in
  the chain competition. "Walk while holding a shield up" then holds by construction.

The real structure of `clientTick`, extended on the existing skeleton:

```java
void clientTick() {
    Minecraft mc = ...; WorldView world = ...;
    if (BotConfig.autoRespawn) AutoRespawn.tick(mc);
    if (CLUTCH.tick(mc, world)) return;          // PANIC: water-bucket fall catch, early-returns past everything
    // ── ambient hand/equipment behaviour: gated by channel ownership, concurrent with movement ──
    runAmbientUseKey(mc);   // autoShield / autoEat / autoHeal arbitrate for the one use key (below)
    if (autoTool && !processOwnsHotbar) AutoTool.tick(mc, mc.player);
    if (autoTotem) AutoTotem.tick(mc, mc.player);   // occupies the off-hand slot; no conflict with the use key
    if (autoEquip) AutoEquip.tick(mc, mc.player);
    // ── movement channel: the scheduler picks the highest-priority chain ──
    scheduler.tick(mc, world, state);
}
```

> **Contention for the use key**, developed further in the combat and defence proposal:
> `AutoShield`, `AutoEat` and `AutoHeal` all want to hold the use key and are therefore mutually
> exclusive. `runAmbientUseKey` arbitrates between them — a shield outranks eating while a projectile
> is incoming, and otherwise emergency healing outranks ordinary eating. This is the natural
> extension of the existing `processOwnsUseKey` gating, with the claimant going from "one process" to
> "a process plus a few ambient users".

## 7. Validation (`validation/40_scheduler.js`)

1. Start `mc.bot.goto` towards a distant point and confirm the bot is walking.
2. `/summon zombie` beside the bot and let it take damage. Assert that `mc.bot.status` shows
   CombatChain active and UserTaskChain with `suspended:true`.
3. Kill or remove the zombie. Assert that the `goto` resumes by itself — status returns to
   UserTaskChain active — and that the bot walks towards its original goal again, which is what
   verifies the forced replan.
4. Assert byte-level parity of the `mc.bot.status` snapshot across all three transports: in-process
   JavaScript, WebSocket RPC, and MCP.

## 8. Open questions

- Should several user tasks queue? Only one foreground task runs at a time today. Should
  `UserTaskChain` keep an internal queue, so that "finish mining the wood, then make a crafting
  table" is one request? The inclination is **not to queue inside the mod**: sequencing is the
  external planner's job, and the mod should expose only the current task plus its preemption and
  resumption.
- Should chain registration be pluggable at runtime, so that a script could register its own chain?
  Boss playbooks may want this. To be evaluated when playbooks are built.
