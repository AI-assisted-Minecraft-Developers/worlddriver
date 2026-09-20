# The perception layer and the decision boundary, as originally written

> **Archived, written 2026-06-04, in Chinese. Not a description of current behaviour.**
>
> This states the rule for deciding which layer a decision belongs to — computable within a tick
> means a reflex, a bounded procedure with a success test means a process, anything needing
> judgement belongs to the caller — and records the misalignments that existed when it was
> written, with a note on each saying whether it had been fixed.
>
> The rule survives and is stated in `docs/design/perception-and-decision-layers.md`. The
> misalignment list is stale: several entries are fixed, and one promise made here was reversed.
> Shelter-seeking was placed below the user task so that it could never preempt an active task,
> and it has since been given a second tier that can, because a bot walking somewhere at nightfall
> could otherwise never stop to save itself.
>
> The scene query parameters proposed here for the client-side read landed on the server-side
> observation instead; the client read takes no parameters.
>
> The original numbered the three layers L0, L1 and L2 — reflex, process, and external caller. That
> notation is kept below only where the document is talking about its own scheme.

## 1. The problem: pulling the caller into the reactive loop kills the bot

The deaths that kept recurring in unassisted survival runs were not caused by the terrain. Their root
cause was that **decisions had been put at the wrong layer**:

- The external language model — the caller, L2 in this document's numbering — was driving evasion and
  flight at model latency, while hostile mobs deal 2–4 HP per second. That produced death after
  death. Reactive survival has to be algorithmic and run inside the engine's tick; a language model
  should only be setting strategy.
- Flight pathing walked a low-health, unarmoured bot off cliffs and into deep water. `Goal.RunAway`
  maximises distance, and a soft `dangerCost` at the edge loses to "further away".
- Perception was assembled by stitching together five separate observe calls, and what the server saw
  could drift out of sync with what the client-side reflexes saw.

This document fixes a **rule for assigning a decision to a layer**, and turns the known misalignments
into an actionable backlog. The load-bearing foundation underneath it is the new `WorldModel`.

## 2. `WorldModel`: one perception substrate shared by all three layers

`WorldModel` (`bot/world/WorldModel.java`) is computed once per tick at the very top of `clientTick`
and publishes an immutable `Snapshot` to a volatile field, the same off-thread snapshot pattern that
`BotState` and `ProcessScheduler` already use. It answers **only "what is true around me right now",
and takes no decisions at all** — decisions stay in their own layers.

```
        WorldModel  (derived facts, recomputed every tick — no decisions, no side effects)
        ╱            │                         ╲
   L0 reflexes     L1 processes              L2 caller (snapshot)
   ClientWorldView  via WorldView.dangerCost  mc.client.scene + ASCII map
   DuskSecureChain  reads HazardField         mc.observe.scene (server overview)
```

- **A pure-function seam.** `HazardField`, `SurvivalFacts`, `AsciiMapRenderer` and `SurvivalMath` take
  only a `WorldView` plus scalars and touch no client types, so they can be asserted from headless
  StageWright scenes, where the server side comes in through `ServerWorldView`. See
  `validation/50_scene_hazard.js` and `51_scene_facts.js`.
- **The client is authoritative for the client's own view.** `mc.client.scene` is forced to read the
  client `WorldModel`, the same source the reflexes act on, so perception and action are physically
  incapable of disagreeing. Removing that possibility is the whole point of the design, because that
  disagreement was the defect.
- **Side effects belong to the caller of the model.** The edge-triggered `duskExposed` and `cornered`
  events are emitted by `BotApiImpl.clientTick`, not from inside `WorldModel`, so the blackboard stays
  clean.

## 3. The layering rule, as a single test

> **Can the correct answer be computed from local state in under a tick?** Then it is a **reflex**:
> algorithmic, in-tick, and it never goes through a language model.
>
> **Is it a bounded procedure with a clear success test?** Then it is a **process** — a skill,
> closing its loop over a few seconds.
>
> **Does it need judgement, a goal, world knowledge, or disambiguation?** Then it belongs to the
> **caller**: event-driven, and never inside a tick.

## 4. The classification

| Decision | Layer | `WorldModel` field or mechanism it reads |
|---|---|---|
| Evading a creeper blast or a projectile | reflex (PanicChain/DodgeChain) | threat position and velocity |
| Fleeing without stepping into a lethal cell (cliff, deep water, lava) | reflex (ClientWorldView.dangerCost) | `HazardField.lethalPenalty` |
| Escaping suffocation, swimming for shore | reflex (AntiSuffocate/AutoSwim) | inWall / inWater |
| Disengaging at low health | reflex (RetreatChain) | health plus threats |
| Seeking shelter at nightfall (idle, exposed, no threat) | reflex (DuskSecureChain, priority 40) | `exposedAtNight` + `cornered` + threats |
| goto / mine / craft / bunker | process (BotProcess) | a goal plus a success test |
| Which resource route or recipe tree to take | process (mc.plan.acquire) | inventory plus recipes |
| What to build, where to put a base, whether to fight or withdraw | caller | the whole scene plus memory (a later piece of work) |
| Reading chat, ordering long-range goals | caller | the event stream plus goals |

## 5. The misalignment list, as a backlog

This is meant to be an actionable list of who fixes what and where it belongs, not a philosophical
one:

1. **The caller was doing reactive flight, and that kills.** Demonstrated in this session's runs.
   Fixed here by pushing hazard-aware flight down into the reflex layer, injecting the lethal penalty
   from `HazardField` into `dangerCost`, without touching `RetreatChain`. **Fixed.**
2. **Seeking shelter at nightfall was entirely the caller's job**, which meant the caller had to watch
   the sky. Pushed down into an idle reflex, `DuskSecureChain` at priority 40, below the user task,
   with a `duskExposed` event reported upwards so the caller can preempt it with a better plan.
   **Fixed.** Note that this is the restrained version of the behaviour: an earlier autonomous
   bunkering chain had been unregistered for being too unpredictable, and this one fires only when the
   bot is idle, exposed, free of threats, and past a debounce.
3. **`cornered`, meaning no safe way out.** This work deliberately does **not** bunker automatically,
   both out of respect for the history behind the previous entry and because bunkering while
   surrounded by melee attackers is a way to die. Flight stops naturally at the safest reachable cell,
   because lethal cells are priced so highly, and `cornered` is reported as an event so the caller can
   choose to bunker, fight, or pillar up. **Holding the safe cell and reporting the event are both
   implemented.**
4. **`retreatHpThreshold` cannot be written over MCP**, so the caller cannot adjust a reflex-layer
   dial. **Not fixed** at the time of writing; a wiring gap, deferred.
5. **There is no reflex for fighting skeletons or spiders while unarmoured**, which leaves a survival
   decision stranded with the caller. **Deferred to the combat work** — breaking line of sight, using
   cover, and strafing.
6. **Direction, biome and vertical cross-section in perception queries** — a vertical plane, a
   facing-relative extent, a biome overlay. This work implemented centre, radius, extent clamping, a
   height overlay and a lethal-cell glyph; the vertical cross-section and facing-relative queries are
   **deferred**.

## 6. Work that follows this document

- **A brain and a memory.** Persistent resources, bases, a death log and explored territory, plus a
  goal loop that consumes `WorldModel` and memory to decide what to do over time.
- **Task scheduling.** A multi-step plan graph — the queuing question the execution-model proposal
  deferred in its open questions.
- **Combat.** Fighting skeletons unarmoured, strafing, and an automatic response to being cornered,
  which is the fifth misalignment above.

`WorldModel` is the shared substrate for all three: the brain work wraps it into a full world model
plus memory, the scheduling work arranges intents on top of it, and the combat work adds
line-of-sight and cover fields to it.
