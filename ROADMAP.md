# Roadmap

WorldDriver's long-term goal is an agent that survives in Minecraft without cheating:
no creative mode, no teleports, no items conjured into the inventory. The control
architecture is the means; survival progress is the measure. This file records the
order in which the remaining work has to happen and why one item depends on another.
It is not a status board — what a release actually changed is in
[`CHANGELOG.md`](CHANGELOG.md).

The architecture these items build on is described in [`docs/dev/bot-layering.md`](docs/dev/bot-layering.md),
and the decisions behind individual pieces are in [`docs/design/`](docs/design/).

## The layering every item below assumes

Decisions are placed in one of three layers, and putting a decision in the wrong layer
is the failure the layering exists to prevent. A reflex runs inside a game tick with a
fixed algorithm and never consults a language model, because anything that waits for a
network round trip is already too slow to stop a fall or a creeper. A process is a
bounded skill that closes its own loop over a few seconds — walk there, mine that,
build this. An agent is the external language model; it is event-driven, it decides
strategy, and it never runs inside a tick.

`ProcessScheduler` arbitrates between them by bid, with a hysteresis margin so two
near-equal chains do not trade the movement channel every tick. The bands are declared
in `bot/scheduler/Priorities.java`; read them there rather than from prose, because
they are tuned.

## In place

These are done, and are listed because later items depend on them rather than as a
record of activity.

- **The scheduler and the reflex chains.** `ProcessScheduler`, the user-task chain,
  and the movement reflexes: panic, dodge, retreat, drown escape, an emergency bunker
  when cornered, and a proactive dusk shelter.
- **Recipe knowledge and goal-directed acquisition.** The bot reads the game's own
  recipe table instead of carrying hardcoded recipes, resolves a sub-recipe tree from
  what is in the inventory, and `mc.plan.acquire` plans the chain for "get me N of X".
  Reusable skills persist through `mc.skill`.
- **Boss sensing and playbooks.** Boss-fight perception plus a hot-reloadable
  multi-phase script runner.
- **The perception layer and the decision boundary.** `WorldModel` recomputes derived
  facts once per tick and publishes an immutable snapshot; it makes no decisions and
  has no side effects. Around it sit pure, headless-testable seams — `HazardField` and
  `HazardCell` for cells that kill, `SurvivalMath` and `SurvivalFacts` for the
  derived survival predicates, `AsciiMapRenderer` for the glanceable map. Hazard
  avoidance is a pathfinding cost rather than a separate chain, and the client-side
  read (`mc.client.scene`) shares its source with the reflexes, so it cannot disagree
  with what the reflexes see.
- **Locomotion, digging and item use no longer fight the human at the keyboard.**
  Movement drives the player's own input object rather than the shared `KeyMapping`
  singletons, which vanilla then serialises into the ordinary movement packets.
  Digging and item use go through `ClientIntents` and a mixin that widens vanilla's
  own keybind reads, so start, hold and release remain vanilla's code. A test refuses
  any new write to the shared attack or use keybinds outside that mixin.

## In progress

**Finish removing the bot from the shared input path.** Two channels are left. Combat
has no strafing, because the movement command currently forces the sideways impulse to
zero; circling a target needs a two-dimensional command. And single-shot actuations —
hold an item, aim, right-click, place — are routed separately from the per-tick process runs,
which means a bot driven by one helm can have its aim and its held slot written by
the other. Originating single-shot actions from the client tick chain is the coherent
fix and is not built.

## Planned, in dependency order

Each item is a spec, a plan and an implementation, and each reuses `WorldModel` as its
shared substrate. The order is a dependency order, not a preference.

1. **Memory across sessions.** Persist resources, base locations, deaths and explored
   territory, and wrap `WorldModel` into a full world model the agent can reason over
   between sessions. Everything after this needs somewhere to remember a decision, and
   a multi-session goal such as reaching the Nether is unreachable without it.
2. **Multi-step task scheduling.** A plan expressed as a directed acyclic graph of
   intentions, scheduled above the world model. This needs the memory layer first,
   because a plan that cannot survive a disconnect is a plan for one session.
3. **Combat content.** Fighting a skeleton or a spider with no armour — breaking line
   of sight, taking cover, keeping distance — is still a decision the external agent
   has to make, which means it is made too slowly. Closing it needs line-of-sight and
   cover fields in `WorldModel`, so it follows the two items above.
4. **Perception increments.** Vertical-plane queries, facing-relative queries, and a
   biome overlay. These are additive and deliberately last: each is cheap on its own
   and none of the items above is blocked by them.

## Survival milestones

The milestones are how the control architecture is judged. Nothing is granted: world
edits are confined to test arenas, and progress in a real world has to come from the
same verbs an external agent has.

| In order | What it takes |
|---|---|
| Recover from a death and reach a wooden pickaxe | Respawn, leave a hostile spawn point, fell a tree, craft up. Reached. |
| Anchor the respawn point with a bed | Craft a bed, sleep in it, and keep it as the point deaths return to. |
| Stone tools and a standing food supply | Partly reached; the food half is what is missing. |
| Iron enough for two buckets and flint and steel | Find and smelt iron; the buckets are what makes the next milestone possible without mining obsidian. |
| Cast obsidian, at least ten blocks of it | Pour water onto lava in place, repeatedly, without drowning or falling in. |
| Build the portal frame, light it, and enter the Nether | The frame, the ignition, and surviving arrival. |

The groundwork already verified against live worlds: underwater pathfinding and
getting ashore, reaching logs above head height, digging and sealing a one-block
shelter, cutting a staircase out of a pit, reaching across water to mine, and
configuration that persists across a restart.

## Deferred, with the reason

- **Bare-handed ranged combat has no reflex.** Deferred to the combat item above
  rather than patched ahead of it, because a reflex without line-of-sight and cover
  data in `WorldModel` would be guessing.
- **Being cornered does not by itself commit the bot to a bunker.** Sealing yourself
  in while surrounded by melee attackers is a way to die, so being cornered is
  reported as an event and the emergency bunker reflex has its own entry conditions
  (low health and several hostiles close by) rather than firing on the cornered signal
  alone.
